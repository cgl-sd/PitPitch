"""Bundle exact provider crest URLs from packaged ESPN team records; no guessed replacements.
Run --fetch to download/refresh the asset catalog, or --check for offline verification.
Gradle compiles these committed assets without downloading anything.
"""
import argparse,concurrent.futures,datetime,hashlib,io,json,pathlib,struct,time,urllib.request,zipfile,shutil
ROOT=pathlib.Path(__file__).resolve().parents[2]
SPORTS=ROOT/'app/src/main/assets/sports';OUT=ROOT/'app/src/main/assets/team-badges'
def references():
 refs={}
 def visit(x):
  if isinstance(x,dict):
   if x.get('id') and x.get('displayName'):
    url=x.get('logo') or next(iter(x.get('logos') or []),{}).get('href')
    if isinstance(url,str) and url.startswith('https://') and '/teamlogos/soccer/' in url:
     row=refs.setdefault(url,{'teams':{}});row['teams'][str(x['id'])]=x['displayName']
   for v in x.values():visit(v)
  elif isinstance(x,list):
   for v in x:visit(v)
 for entry in json.loads((SPORTS/'catalog.json').read_text()).values():visit(json.loads((SPORTS/entry['file']).read_text()))
 return refs

def extension(body):
 if body[:4]==b'RIFF' and body[8:12]==b'WEBP':return 'webp'
 if body[:8]==b'\x89PNG\r\n\x1a\n':return 'png'
 if body[:2]==b'\xff\xd8':return 'jpg'
 raise ValueError('Provider did not return a supported image')
def validate(body):
 kind=extension(body)
 if kind=='webp':
  from PIL import Image
  with Image.open(io.BytesIO(body)) as image:w,h=image.size
  assert 0<w<=4096 and 0<h<=4096
  return w,h
 if kind=='png':w,h=struct.unpack('>II',body[16:24])
 else:
  pos=2;w=h=0
  while pos<len(body):
   assert body[pos]==255;marker=body[pos+1];pos+=2
   if marker==255:pos-=1;continue
   length=int.from_bytes(body[pos:pos+2],'big')
   if marker in (192,193,194,195,197,198,199,201,202,203,205,206,207):
    h,w=struct.unpack('>HH',body[pos+3:pos+7]);break
   pos+=length
 assert 0<w<=4096 and 0<h<=4096
 return w,h

def fetch():
 OUT.mkdir(parents=True,exist_ok=True);refs=references();previous=json.loads((OUT/'catalog.json').read_text()) if (OUT/'catalog.json').exists() else {};failures={}
 def download(item):
  url,teams=item;prefix=hashlib.sha256(url.encode()).hexdigest();path=OUT/previous.get(url,{}).get('file',prefix+'.png')
  for attempt in range(3):
   try:
    if path.exists():body=path.read_bytes()
    else:
     body=urllib.request.urlopen(url,timeout=25).read();validate(body);path=OUT/(prefix+'.'+extension(body));path.write_bytes(body)
    w,h=validate(body)
    return url,dict(teams,file=path.name,sha256=hashlib.sha256(body).hexdigest(),width=w,height=h,
     fetchedUtc=previous.get(url,{}).get('fetchedUtc') or datetime.datetime.now(datetime.timezone.utc).isoformat())
   except Exception as e:
    if attempt==2:failures[url]=str(e);return None
    time.sleep(1)
 result={}
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
  for item in pool.map(download,refs.items()):
   if item:result[item[0]]=item[1]
 # Exclude artwork referenced only by retired seasons; keep a recoverable local backup.
 backup=ROOT/'tmp/validation/archive-before-2020-scope/team-badges';backup.mkdir(parents=True,exist_ok=True)
 used={entry['file'] for entry in result.values()}
 for path in OUT.iterdir():
  if path.suffix in ('.png','.jpg') and path.name not in used:shutil.copy2(path,backup/path.name);path.unlink()
 (OUT/'catalog.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 (ROOT/'tmp/validation/team-badges-failures.json').write_text(json.dumps(failures,indent=2)+'\n')
 print(f'Bundled {len(result)}/{len(refs)} provider URLs; {len(failures)} failures',flush=True)

def check():
 refs=references();catalog=json.loads((OUT/'catalog.json').read_text());missing=[]
 for url,entry in catalog.items():
  body=(OUT/entry['file']).read_bytes();assert hashlib.sha256(body).hexdigest()==entry['sha256'];assert validate(body)==(entry['width'],entry['height'])
  assert url in refs and entry['teams']==refs[url]['teams'];assert entry['file']==hashlib.sha256(url.encode()).hexdigest()+'.'+extension(body)
 for url in refs:
  if url not in catalog:missing.append(url)
 print(f'PASS: {len(catalog)} authentic packaged crests verified, {len(missing)} source URLs unavailable')
 return missing
def check_apk(path):
 count=0
 with zipfile.ZipFile(path) as apk:
  for directory in ['rounds','team-badges']:
   for file in (ROOT/'app/src/main/assets'/directory).iterdir():
    assert apk.read(f'assets/{directory}/{file.name}')==file.read_bytes(),file
    count+=1
 print(f'PASS: {count} round/crest asset files are present byte-for-byte in the APK')
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--fetch',action='store_true');parser.add_argument('--check',action='store_true');parser.add_argument('--apk',type=pathlib.Path);args=parser.parse_args()
 if args.fetch:fetch()
 if args.check:check()
 if args.apk:check_apk(args.apk)
 if not(args.fetch or args.check or args.apk):print(f'{len(references())} provider crest URLs')
