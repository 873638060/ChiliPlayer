package car.salt.player;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import org.json.*;

/** Validated snapshots with a separate last-good recovery copy. */
final class Store {
    static byte[] checked(File file)throws Exception{
        byte[] bytes=Library.bytes(new AtomicFile(file).openRead(),64*1024*1024);
        Object value=new JSONTokener(new String(bytes,"UTF-8")).nextValue();
        if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new IOException("数据格式错误");
        return bytes;
    }
    static boolean exists(File file){return file.exists()||new File(file+".bak").exists();}
    static synchronized byte[] read(Context c,String name)throws IOException{
        File file=new File(c.getFilesDir(),name),good=new File(c.getFilesDir(),name+".good");
        if(!exists(file)&&!exists(good))return null;
        try{return checked(file);}catch(Exception failure){
            try{byte[] bytes=checked(good);Journal.event(c,name+" 读取异常，已恢复有效备份");return bytes;}
            catch(Exception second){Journal.event(c,name+" 读取失败，保留原文件 · "+failure.getClass().getSimpleName());throw new IOException(name+" 数据无法读取，原文件已保留",failure);}
        }
    }
    static void write(File file,byte[] bytes)throws IOException{
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=atomic.startWrite();
        try{out.write(bytes);atomic.finishWrite(out);}catch(IOException e){atomic.failWrite(out);throw e;}
    }
    static synchronized void save(Context c,String name,byte[] bytes)throws IOException{
        if(bytes.length>64*1024*1024)throw new IOException("数据超出保存上限，保留旧快照");
        File file=new File(c.getFilesDir(),name),good=new File(c.getFilesDir(),name+".good");
        byte[] old=read(c,name); // Refuse to overwrite an unreadable snapshot without recovery.
        if(old!=null)write(good,old);
        write(file,bytes);
        if(old==null)write(good,bytes);
    }
}
