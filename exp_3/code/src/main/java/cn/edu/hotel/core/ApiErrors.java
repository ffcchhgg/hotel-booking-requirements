package cn.edu.hotel.core;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Map<String, String>> business(BusinessException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("code", exception.code(), "message", exception.getMessage()));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Map<String, String>> login(AuthenticationException exception) {
    return ResponseEntity.status(401)
        .body(Map.of("code", "INVALID_CREDENTIALS", "message", "用户名或密码不正确"));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MaxUploadSizeExceededException.class,
    MissingRequestValueException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<Map<String, String>> input(Exception exception) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_INPUT", "message", "输入格式不正确或文件超过5MB"));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<Map<String, String>> database(DataAccessException exception) {
    // Exception text may contain SQL parameters. Do not include it in logs or responses.
    LOG.warn("Database operation rejected: {}", exception.getClass().getSimpleName());
    return ResponseEntity.status(409)
        .body(Map.of("code", "DATA_CONFLICT", "message", "数据发生冲突，本次操作未完成，请重新获取最新状态"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
    LOG.error("Request failed: {}", exception.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(Map.of("code", "INTERNAL_ERROR", "message", "操作未完成，请刷新查询结果后重试"));
  }
}
