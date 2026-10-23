#!/usr/bin/env python3
"""Version every packaged input, offline; rerun after changing local assets."""
from pathlib import Path
import hashlib,json,argparse
root=Path(__file__).resolve().parents[2]/'shared/assets'
p=argparse.ArgumentParser();p.add_argument('--verify',action='store_true');args=p.parse_args()
files={str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(root.rglob('*')) if f.is_file() and f.name not in ('local-backend-manifest.json','local-backend-revision.txt')}
revision=hashlib.sha256(json.dumps(files,sort_keys=True,separators=(',',':')).encode()).hexdigest()
manifest={'revision':revision,'files':files}
path=root/'local-backend-manifest.json'
if args.verify:
 assert json.loads(path.read_text())==manifest,'Packaged inputs changed: regenerate backend manifest before building'
 assert (root/'local-backend-revision.txt').read_text().strip()==revision
 print('Verified',len(files),'versioned local inputs')
else:
 path.write_text(json.dumps(manifest,indent=2)+'\n');(root/'local-backend-revision.txt').write_text(revision+'\n');print('Versioned',len(files),'local inputs')
