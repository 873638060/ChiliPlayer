package car.salt.player;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

final class SourceAccess {
    static List<Uri> candidates(Context context,Uri original){
        LinkedHashSet<Uri> choices=new LinkedHashSet<>();
        choices.add(original);
        if("content".equals(original.getScheme())){
            try{
                String doc=DocumentsContract.getDocumentId(original);
                for(UriPermission grant:context.getContentResolver().getPersistedUriPermissions()){
                    if(!grant.isReadPermission()||!Objects.equals(original.getAuthority(),grant.getUri().getAuthority()))continue;
                    try{String root=DocumentsContract.getTreeDocumentId(grant.getUri());
                        if(doc.equals(root)||doc.startsWith(root.endsWith(":")?root:root+"/"))choices.add(DocumentsContract.buildDocumentUriUsingTree(grant.getUri(),doc));
                    }catch(Exception ignored){}
                }
                choices.add(DocumentsContract.buildDocumentUri(original.getAuthority(),doc));
                // Only the external-storage provider defines volume:relative-path identities.
                if("com.android.externalstorage.documents".equals(original.getAuthority())){
                    int colon=doc.indexOf(':');
                    if(colon>0){
                        String volume=doc.substring(0,colon),relative=doc.substring(colon+1);
                        LinkedHashSet<File> roots=new LinkedHashSet<>();
                        if("primary".equals(volume))roots.add(Environment.getExternalStorageDirectory());
                        else roots.add(new File("/storage",volume));
                        Object manager=context.getSystemService(Context.STORAGE_SERVICE);
                        // Android 6 vendor firmware often exposes the UUID and actual USB mount path here.
                        for(String method:new String[]{"getVolumeList","getVolumes"}){
                            try{
                                Object values=manager.getClass().getMethod(method).invoke(manager);
                                Iterable<?> volumes=values instanceof List?(List<?>)values:Arrays.asList((Object[])values);
                                for(Object item:volumes){
                                    String uuid=value(item,"getUuid"),fsUuid=value(item,"getFsUuid");
                                    Object pathObject=invoke(item,"getPath");
                                    if(pathObject==null)continue;
                                    File root=pathObject instanceof File?(File)pathObject:new File(pathObject.toString());
                                    boolean primary="primary".equals(volume)&&Boolean.TRUE.equals(invoke(item,"isPrimary"));
                                    if(primary||volume.equalsIgnoreCase(uuid)||volume.equalsIgnoreCase(fsUuid)||volume.equalsIgnoreCase(root.getName()))roots.add(root);
                                }
                            }catch(Exception ignored){}
                        }
                        for(File base:roots){
                            File file=new File(base,relative).getCanonicalFile();
                            if(file.getPath().startsWith(base.getCanonicalPath()+File.separator))choices.add(Uri.fromFile(file));
                        }

                    }
                }
            }catch(Exception ignored){}
        }
        return new ArrayList<>(choices);
    }
    static Object invoke(Object object,String method){
        try{return object.getClass().getMethod(method).invoke(object);}catch(Exception ignored){return null;}
    }
    static String value(Object object,String method){Object result=invoke(object,method);return result==null?"":result.toString();}
    static void metadata(Context context,android.media.MediaMetadataRetriever reader,Uri original)throws IOException{
        for(Uri uri:candidates(context,original)){
            try{
                if("file".equals(uri.getScheme()))reader.setDataSource(uri.getPath());
                else reader.setDataSource(context,uri);
                return;
            }catch(Exception ignored){}
        }
        throw new IOException("无法读取音频元数据");
    }
    static InputStream open(Context context,Uri original)throws IOException{
        IOException failure=new IOException("存储暂未就绪");
        for(Uri uri:candidates(context,original)){
            try{
                InputStream stream=context.getContentResolver().openInputStream(uri);
                if(stream!=null)return stream;
            }catch(IOException|SecurityException ex){
                Journal.event(context,"读取来源失败 scheme="+uri.getScheme()+" provider="+uri.getAuthority()+" · "+ex.getClass().getSimpleName());
                failure=new IOException("文件读取失败",ex);
                Journal.event(context,"候选文件 "+uri.buildUpon().clearQuery().fragment(null).build().toString());
            }
        }
        throw failure;
    }
    /** Convert non-seekable document pipes to a seekable, private file for older decoders. */
    static File cache(Context context,Uri uri,java.util.concurrent.Callable<Boolean> cancelled)throws Exception{
        File file=File.createTempFile("playing-",".audio",context.getCacheDir());
        boolean complete=false;
        try(InputStream in=open(context,uri);OutputStream out=new FileOutputStream(file)){
            byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1){
                if(cancelled.call())throw new InterruptedIOException();
                if(file.getUsableSpace()<4*1024*1024)throw new IOException("可用存储空间不足");
                out.write(buffer,0,count);
            }
            complete=true;
        }finally{if(!complete)file.delete();}
        return file;
    }
    static void writeLog(Context context,Uri destination,byte[] data)throws IOException{
        IOException failure=new IOException("无法写入目标文件");
        for(String mode:new String[]{"wt","w"}){
            try(OutputStream out=context.getContentResolver().openOutputStream(destination,mode)){
                if(out==null)throw new IOException("目标不可写");
                out.write(data);
                return;
            }catch(IOException|IllegalArgumentException ex){failure=new IOException("无法写入目标文件",ex);}
        }
        throw failure;
    }
}
