import hashlib, json, pathlib, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
base=pathlib.Path(__file__).parent
state={"mode":"normal"}
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        url=urllib.parse.urlsplit(self.path)
        q={k:v[0] for k,v in urllib.parse.parse_qs(url.query).items()}
        name=url.path.split('/')[-1].replace('.view','')
        ok=q.get('u')=='demo' and q.get('t')==hashlib.md5(('demo'+q.get('s','')).encode()).hexdigest()
        with (base/'server-check.log').open('a') as log: log.write(name+' '+str(ok)+'\n')
        if not ok:self.send_error(403);return
        if name=='qaMode':state['mode']=q.get('value','normal')
        if name=='getAlbumList2' and state['mode'] in ('legacy','fail'):self.send_error(404 if state['mode']=='legacy' else 503);return
        song={'id':'s1','title':'Server Test','artist':'Car Test','album':'Road Album','coverArt':'art-1','replayGain':{'trackGain':-5,'trackPeak':0.9}}
        r={'status':'ok','version':'1.16.1'}
        if name=='getAlbumList2':r['albumList2']={'album':[{'id':'missing'},{'id':'a1','name':'Road Album','artist':'Car Test'}]}
        if name=='getAlbumList2' and state['mode']=='slow':r['albumList2']={'album':[{'id':'a1'},{'id':'slow'}]}
        if name=='getAlbum' and q.get('id')=='slow':__import__('time').sleep(15)
        if name=='getAlbumList':r['albumList']={'album':[{'id':'missing'},{'id':'a1'}]}
        if name=='getAlbum' and q.get('id')=='missing':self.send_error(404);return
        if name=='getAlbum':r['album']={'id':'a1','song':[{},song]}
        if name=='search3':self.send_error(400);return
        if name=='getLyrics':r['lyrics']={'value':'[00:00.00]Network test line'}
        if name=='getCoverArt' and q.get('id')=='art-1':data=(base/'cover-test.png').read_bytes();kind='image/png'
        elif name=='stream':data=(base/'fixtures'/'03 Waves.wav').read_bytes();kind='audio/wav'
        else:data=json.dumps({'subsonic-response':r}).encode();kind='application/json'
        self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
ThreadingHTTPServer(('127.0.0.1',int(__import__('os').environ.get('NEWSALT_QA_PORT','18053'))),Handler).serve_forever()

