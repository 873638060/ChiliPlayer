package car.salt.player;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
public class PipeProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 public String getType(Uri u){return "audio/mpeg";}
 public Cursor query(Uri u,String[] p,String s,String[] a,String o){
  if(u.toString().contains("slowScan"))try{Thread.sleep(4000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  android.database.MatrixCursor result=new android.database.MatrixCursor(p);
  if(u.toString().contains("absent"))return result;
  result.addRow(new Object[]{"song","Pipe.mp3","audio/mpeg",100L,100L});return result;
 }
 public Uri insert(Uri u,ContentValues v){return null;}
 public int delete(Uri u,String s,String[] a){return 0;}
 public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
 public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException {
  if(u.getPath().equals("/slow"))try{Thread.sleep(4000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  if(u.getPath().equals("/log")){
   if(mode.equals("r"))return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),"export.txt"),ParcelFileDescriptor.MODE_READ_ONLY);
   if(mode.equals("wt"))throw new FileNotFoundException("legacy mode");
   return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),"export.txt"),ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_TRUNCATE|ParcelFileDescriptor.MODE_WRITE_ONLY);
  }
  try{
   ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
   new Thread(()->{try(InputStream in=getContext().getAssets().open("01 Drive.mp3");OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(Exception ignored){}}).start();
   return pipe[0];
  }catch(IOException ex){throw new FileNotFoundException();}
 }
}
