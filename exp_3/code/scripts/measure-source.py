"""Count handwritten production sources separately from tests and wrapper/generated files."""
from pathlib import Path
import os
import hashlib
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
evidence = Path(os.environ.get('EVIDENCE_DIR', root.parent / 'evidence'))
counts = []
for file in sorted((root / 'src/main').rglob('*')):
    if file.suffix not in {'.java', '.js', '.css', '.html', '.sql', '.yml'}:
        continue
    lines = file.read_text(encoding='utf-8').splitlines()
    # Conservative transparent measure: nonblank lines, excluding comment-only lines.
    count = sum(bool(line.strip()) and not line.lstrip().startswith(('//', '/*', '*', '--', '<!--')) for line in lines)
    counts.append({'file': file.relative_to(root).as_posix(), 'physicalLines': len(lines), 'nonblankNonCommentOnlyLines': count})
tests = []
for folder in ['surefire-reports', 'failsafe-reports']:
    for file in (root / 'target' / folder).glob('TEST-*.xml'):
        suite = ET.parse(file).getroot()
        tests.append({key: suite.attrib.get(key) for key in ['name', 'tests', 'failures', 'errors', 'skipped', 'time']})
jar = root / 'target/hotel-booking.jar'
report = {'countingRule': 'Handwritten src/main files only; excludes tests, Maven Wrapper, node_modules, images, UML and generated output. Nonblank measure excludes comment-only lines but includes braces; this is not a claim of independent statements.',
          'javaNonblankNonCommentOnlyLines': sum(x['nonblankNonCommentOnlyLines'] for x in counts if x['file'].endswith('.java')),
          'productionNonblankNonCommentOnlyLines': sum(x['nonblankNonCommentOnlyLines'] for x in counts),
          'tests': tests, 'testTotal': sum(int(x['tests']) for x in tests), 'files': counts,
          'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest() if jar.exists() else None}
evidence.mkdir(exist_ok=True)
(evidence/'build-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['files','countingRule']},ensure_ascii=False,indent=2))
