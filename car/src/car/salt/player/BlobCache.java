package car.salt.player;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Account-scoped, bounded private cache. Same-key requests share one fetch. */
final class BlobCache {
    interface Loader { byte[] load() throws Exception; }
    static final Object[] locks=new Object[16];
    static {for(int i=0;i<locks.length;i++)locks[i]=new Object();}
    static String hash(String key)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8")))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static byte[] get(Context context,String key,int limit,Loader loader)throws Exception{
        synchronized(locks[(key.hashCode()&0x7fffffff)%locks.length]){
            File dir=new File(context.getCacheDir(),"media-blobs");dir.mkdirs();File target=new File(dir,hash(key));AtomicFile file=new AtomicFile(target);
            try{byte[] bytes=Library.bytes(file.openRead(),limit);target.setLastModified(System.currentTimeMillis());return bytes;}catch(IOException ignored){}
            byte[] bytes=loader.load();if(bytes==null||bytes.length==0||bytes.length>limit)return null;
            FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(IOException ex){if(out!=null)file.failWrite(out);Journal.event(context,"媒体缓存写入失败");}
            trim(dir);return bytes;
        }
    }
    static synchronized void trim(File dir){
        File[] files=dir.listFiles((d,n)->n.matches("[0-9a-f]{64}"));if(files==null)return;
        Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long size=0;for(File f:files)size+=f.length();int count=files.length;
        for(File f:files){if(size<=32L*1024*1024&&count<=512)break;long n=f.length();if(f.delete()){size-=n;count--;}}
    }
}
