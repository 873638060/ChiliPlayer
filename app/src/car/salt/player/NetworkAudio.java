package car.salt.player;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import com.un4seen.bass.BASS;

/** Progressive disk buffer. HTTPS uses Android's certificate and hostname validation. */
final class NetworkAudio implements BASS.BASS_FILEPROCS {
    private final File file;
    private final RandomAccessFile reader;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private boolean done;
    private IOException failure;
    private long downloaded,length=-1,position;
    NetworkAudio(Context context,String address)throws IOException {
        file=File.createTempFile("softnet-",".audio",context.getCacheDir());
        reader=new RandomAccessFile(file,"r");
        new Thread(()->download(address),"Soft audio download").start();
    }
    private void download(String address){
        try {
            URL url=new URL(address);HttpURLConnection c=null;
            for(int redirects=0;redirects<6;redirects++){
                if(closed)throw new InterruptedIOException();
                c=(HttpURLConnection)url.openConnection();connection=c;
                c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Accept-Encoding","identity");
                int status=c.getResponseCode();
                if(status>=300&&status<=399){
                    String location=c.getHeaderField("Location");c.disconnect();
                    if(location==null)throw new IOException("Missing redirect");
                    URL next=new URL(url,location);
                    if(!next.getProtocol().equals("http")&&!next.getProtocol().equals("https"))throw new IOException("Invalid protocol");
                    if(url.getProtocol().equals("https")&&!next.getProtocol().equals("https"))throw new IOException("HTTPS downgrade rejected");
                    url=next;c=null;continue;
                }
                if(status!=200)throw new IOException("HTTP "+status);
                break;
            }
            if(c==null)throw new IOException("Too many redirects");
            String size=c.getHeaderField("Content-Length");
            synchronized(this){try{length=size==null?-1:Long.parseLong(size);}catch(NumberFormatException ignored){}notifyAll();}
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){
                byte[] data=new byte[65536];int n;
                while(!closed&&(n=in.read(data))!=-1){
                    if(file.getUsableSpace()<8*1024*1024)throw new IOException("Insufficient cache space");
                    out.write(data,0,n);
                    synchronized(this){downloaded+=n;notifyAll();}
                }
            }
            synchronized(this){if(!closed&&length>=0&&downloaded!=length)throw new EOFException("Truncated response");length=downloaded;done=true;notifyAll();}
        }catch(IOException ex){synchronized(this){failure=ex;notifyAll();}}
        finally {HttpURLConnection c=connection;if(c!=null)c.disconnect();if(closed)file.delete();}
    }
    private void waitData()throws IOException {
        if(closed)throw new InterruptedIOException();
        if(failure!=null)throw failure;
        try{wait(1000);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new InterruptedIOException();}
    }
    public synchronized long FILELENPROC(Object user){try{while(length<0&&!done)waitData();return Math.max(0,length);}catch(IOException ex){return 0;}}
    public synchronized int FILEREADPROC(ByteBuffer buffer,int requested,Object user){
        try{
            while(downloaded-position<requested&&!done)waitData();
            if(closed||failure!=null)return 0;
            int n=(int)Math.min(requested,downloaded-position);
            if(n<=0)return 0;
            byte[] data=new byte[Math.min(n,65536)];reader.seek(position);
            for(int remaining=n;remaining>0;){int part=Math.min(remaining,data.length);reader.readFully(data,0,part);buffer.put(data,0,part);remaining-=part;}
            position+=n;return n;
        }catch(IOException ex){failure=ex;return 0;}
    }
    public synchronized boolean FILESEEKPROC(long offset,Object user){if(closed||offset<0||(length>=0&&offset>length))return false;position=offset;return true;}
    public void FILECLOSEPROC(Object user){close();}
    synchronized boolean failed(){return failure!=null;}
    void cancel(){synchronized(this){closed=true;notifyAll();}HttpURLConnection c=connection;if(c!=null)new Thread(c::disconnect,"Close audio connection").start();}
    void close(){cancel();synchronized(this){try{reader.close();}catch(IOException ignored){}file.delete();}}
}
