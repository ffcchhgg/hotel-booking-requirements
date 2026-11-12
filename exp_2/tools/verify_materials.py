"""Read-only consistency checks for the published design and prototype snapshot."""
from pathlib import Path
import hashlib
import json
from PIL import Image, ImageChops

root = Path(__file__).resolve().parents[1]
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
read = lambda p: json.loads((root / p).read_text(encoding='utf-8'))
for filename in ['checks/ui.json', 'checks/ui_journeys.json', 'checks/guest_count.json']:
    data = read(filename)
    assert data['result'] == 'PASS', filename
    for name, digest in data['source_sha256'].items():
        assert sha(root / name) == digest, name
ui = read('checks/ui.json')
assert len(ui['screenshots']) == 26
assert {s['file'] for s in ui['screenshots']} == {p.name for p in (root / 'deliverables/UI界面').glob('*.png')}
for shot in ui['screenshots']:
    assert sha(root / 'deliverables/UI界面' / shot['file']) == shot['sha256'], shot['file']
for folder in ['UML', 'UML_color']:
    diagrams = sorted((root / 'deliverables' / folder).glob('*.puml'))
    assert len(diagrams) == 9
    for source in diagrams:
        with Image.open(source.with_suffix('.png')) as image:
            embedded = image.info['plantuml'].replace('\r\n', '\n')
            embedded = embedded[:embedded.index('@enduml') + len('@enduml')].strip()
            assert embedded == source.read_text(encoding='utf-8').strip(), source
assets = read('report/assets_color/manifest.json')
assert len(assets) == 21
for entry in assets:
    assert sha(root / entry['source']) == entry['source_sha256']
    target = root / 'report/assets_color' / entry['file']
    assert sha(target) == entry['output_sha256']
    with Image.open(root / entry['source']) as original, Image.open(target) as crop:
        expected = original.crop(tuple(entry['crop_box'])).convert('RGB')
        assert crop.size == expected.size and crop.mode == 'RGB'
        assert ImageChops.difference(crop, expected).getbbox() is None
print('PASS: prototype source hashes, 26 UI screenshots, 9 UML pairs in each palette, 21 report crops.')
