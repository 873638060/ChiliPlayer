package car.salt.soft;

import android.content.*;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

final class Library {
    static byte[] bytes(InputStream input,int limit) throws IOException {
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("文件超过读取上限");out.write(b,0,n);}
            return out.toByteArray();
        }
    }
    static synchronized JSONArray read(Context c,String name){
        try{return new JSONArray(new String(bytes(new AtomicFile(new File(c.getFilesDir(),name)).openRead(),64*1024*1024),"UTF-8"));}
        catch(Exception e){return new JSONArray();}
    }
    static synchronized void save(Context c,String name,JSONArray contents) throws IOException {
        AtomicFile file=new AtomicFile(new File(c.getFilesDir(),name)); FileOutputStream out=file.startWrite();
        try{out.write(contents.toString().getBytes("UTF-8"));file.finishWrite(out);}
        catch(IOException e){file.failWrite(out);throw e;}
    }
    static final String[] COL={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED};
    private static class Dir {
        String id,path;
        Dir(String id,String path){this.id=id;this.path=path;}
    }
    static ArrayList<Track> scan(Context c,Uri tree,List<Track> previous) throws Exception {
        ContentResolver resolver=c.getContentResolver();
        HashMap<String,Track> old=new HashMap<>(); for(Track t:previous)old.put(t.id(),t);
        ArrayList<Track> found=new ArrayList<>(); ArrayDeque<Dir> dirs=new ArrayDeque<>(); HashSet<String> visited=new HashSet<>();
        dirs.add(new Dir(DocumentsContract.getTreeDocumentId(tree),""));
        while(!dirs.isEmpty()){
            Dir dir=dirs.removeFirst();if(!visited.add(dir.id))continue;
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,dir.id);
            HashMap<String,String> lyrics=new HashMap<>(); ArrayList<Track> here=new ArrayList<>();
            try(Cursor rows=resolver.query(children,COL,null,null,null)){
                if(rows==null || rows.getExtras().getBoolean("loading") || rows.getExtras().containsKey("error"))throw new IOException("目录尚未就绪");
                while(rows.moveToNext()){
                    String doc=rows.getString(0),name=rows.getString(1),mime=rows.getString(2);
                    if(name==null)continue;
                    if(Document.MIME_TYPE_DIR.equals(mime)){dirs.add(new Dir(doc,dir.path+name+"/"));continue;}
                    String uri=DocumentsContract.buildDocumentUriUsingTree(tree,doc).toString();
                    String lower=name.toLowerCase(Locale.ROOT);
                    if(lower.endsWith(".lrc")){lyrics.put(lower.substring(0,lower.length()-4),uri);continue;}
                    if(!lower.matches(".*\\.(mp3|flac|wav|ape|ogg|m4a|aac|opus)"))continue;
                    long size=rows.getLong(3),mtime=rows.getLong(4); Track cached=old.get(uri);
                    JSONObject data;
                    if(cached!=null && mtime!=0 && cached.data.optLong("mtime")==mtime && cached.data.optLong("size")==size)
                        data=new JSONObject(cached.data.toString());
                    else {
                        data=new JSONObject();data.put("title",name.substring(0,name.lastIndexOf('.')));
                        MediaMetadataRetriever meta=new MediaMetadataRetriever();
                        try{SourceAccess.metadata(c,meta,Uri.parse(uri));
                            tag(data,meta,"title",7);tag(data,meta,"artist",2);tag(data,meta,"album",1);tag(data,meta,"duration",9);}
                        catch(Exception ignored){}finally{try{meta.release();}catch(Exception ignored){}}
                    }
                    data.put("id",uri).put("file",name).put("folder",dir.path.isEmpty()?"/":dir.path).put("size",size).put("mtime",mtime);
                    data.put("documentId",doc).put("tree",tree.toString());
                    if("com.android.externalstorage.documents".equals(tree.getAuthority())&&doc.indexOf(':')>0){int colon=doc.indexOf(':');data.put("volume",doc.substring(0,colon)).put("relativePath",doc.substring(colon+1));}
                    here.add(new Track(data));
                }
            }
            for(Track t:here){String file=t.get("file");t.data.put("lrc",lyrics.get(file.substring(0,file.lastIndexOf('.')).toLowerCase(Locale.ROOT)));found.add(t);}
        }
        if(found.isEmpty() && !previous.isEmpty())throw new IOException("本次未读到歌曲，已保留原曲库");
        Collections.sort(found,(a,b)->a.title().compareToIgnoreCase(b.title()));
        return found;
    }
    static void tag(JSONObject data,MediaMetadataRetriever meta,String key,int field) throws JSONException {
        String value=meta.extractMetadata(field);if(value!=null&&!value.isEmpty())data.put(key,value);
    }
}
