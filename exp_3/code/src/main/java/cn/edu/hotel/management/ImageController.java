package cn.edu.hotel.management;

import cn.edu.hotel.core.BusinessClock;
import cn.edu.hotel.core.BusinessException;
import cn.edu.hotel.core.Db;
import cn.edu.hotel.core.Input;
import cn.edu.hotel.identity.Actor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ImageController {
  private final Path root;
  private final Db db;
  private final BusinessClock clock;

  public ImageController(@Value("${hotel.uploads}") String directory, Db db, BusinessClock clock) {
    this.root = Path.of(directory).toAbsolutePath().normalize();
    this.db = db;
    this.clock = clock;
  }

  @PostMapping("/api/manage/images")
  public Object upload(
      @AuthenticationPrincipal Actor actor, @RequestParam("file") MultipartFile file)
      throws IOException {
    actor.require("STAFF");
    Input.require(!file.isEmpty() && file.getSize() <= 5 * 1024 * 1024, "图片须为不超过5MB的PNG或JPEG文件");
    BufferedImage decoded;
    try (ImageInputStream input = ImageIO.createImageInputStream(file.getInputStream())) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      Input.require(readers.hasNext(), "无法识别图片内容");
      ImageReader reader = readers.next();
      try {
        String format = reader.getFormatName();
        Input.require(
            format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg"), "仅支持PNG或JPEG图片");
        reader.setInput(input, true, true);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        Input.require(pixels > 0 && pixels <= 20000000, "图片分辨率过大，最多2000万像素");
        decoded = reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (IOException exception) {
      throw BusinessException.invalid("图片损坏或格式不正确");
    }
    UUID id = UUID.randomUUID();
    String filename = id + ".png";
    Files.createDirectories(root);
    Path path = root.resolve(filename);
    // Re-encode pixels to remove original metadata and embedded non-image payloads.
    ImageIO.write(decoded, "png", path.toFile());
    try {
      db.jdbc.update(
          "INSERT INTO uploaded_image(id,hotel_id,owner_id,file_name,content_type,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          actor.hotelId(),
          actor.id(),
          filename,
          "image/png",
          Db.time(clock.now()));
    } catch (RuntimeException exception) {
      Files.deleteIfExists(path);
      throw exception;
    }
    return Map.of("url", "/api/images/" + id);
  }

  @GetMapping("/api/images/{id}")
  public ResponseEntity<FileSystemResource> read(
      @AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
    Map<String, Object> item = db.one("SELECT * FROM uploaded_image WHERE id=?", id);
    String url = "/api/images/" + id;
    long published =
        db.jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM hotel WHERE image=?)+(SELECT COUNT(*) FROM room_type"
                + " WHERE image=?)",
            Long.class,
            url,
            url);
    boolean authorized =
        actor != null
            && (actor.is("ADMIN")
                || (actor.is("STAFF") && actor.hotelId() == Db.value(item, "hotel_id")));
    if (published == 0 && !authorized) {
      throw BusinessException.missing();
    }
    Path path = root.resolve(Db.string(item, "file_name")).normalize();
    if (!path.startsWith(root) || !Files.isRegularFile(path)) {
      throw BusinessException.missing();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .header("X-Content-Type-Options", "nosniff")
        .body(new FileSystemResource(path));
  }
}
