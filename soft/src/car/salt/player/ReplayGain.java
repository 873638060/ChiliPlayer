package car.salt.soft;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import org.json.*;

/** Bounded readers for ID3v2 TXXX and FLAC Vorbis comments. */
final class ReplayGain {
    static Double number(String value){
        try{double n=Double.parseDouble(value.trim().replaceFirst("(?i)\\s*dB$", ""));return Double.isNaN(n)||Double.isInfinite(n)?null:n;}catch(Exception e){return null;}
    }
    static double db(JSONObject tags){
        if(tags==null)return 0;
        Double gain=number(tags.optString("trackGain", ""));String peakKey="trackPeak";
        if(gain==null){gain=number(tags.optString("albumGain",""));peakKey="albumPeak";}
        if(gain==null)return 0;
        double result=Math.max(-60,Math.min(20,gain));
        Double peak=number(tags.optString(peakKey,""));
        if(peak!=null&&peak>0)result=Math.min(result,-20*Math.log10(peak));
        return Math.max(-60,result);
    }
    static void tag(JSONObject tags,String key,String value)throws JSONException{
        String k=key.trim().toUpperCase(Locale.ROOT);String field=null;
        if(k.equals("REPLAYGAIN_TRACK_GAIN"))field="trackGain";
        if(k.equals("REPLAYGAIN_TRACK_PEAK"))field="trackPeak";
        if(k.equals("REPLAYGAIN_ALBUM_GAIN"))field="albumGain";
        if(k.equals("REPLAYGAIN_ALBUM_PEAK"))field="albumPeak";
        Double n=number(value);if(field!=null&&n!=null)tags.put(field,n);
    }
    static int integer(byte[] b,int p,boolean sync){int n=0;for(int i=0;i<4;i++)n=(n<<(sync?7:8))|(b[p+i]&(sync?127:255));return n;}
    static int little(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
    static void skip(DataInputStream in,int n)throws IOException{while(n>0){int used=in.skipBytes(n);if(used==0){in.readByte();used=1;}n-=used;}}
    static JSONObject read(InputStream source)throws Exception{
        JSONObject tags=new JSONObject();BufferedInputStream buffered=new BufferedInputStream(source);buffered.mark(10);DataInputStream in=new DataInputStream(buffered);
        byte[] head=new byte[10];in.readFully(head);buffered.reset();
        if(head[0]=='I'&&head[1]=='D'&&head[2]=='3'){
            int version=head[3]&255,size=integer(head,6,true);if((version!=3&&version!=4)||size<0||size>4*1024*1024||(head[5]&128)!=0)return tags;
            skip(in,10);byte[] data=new byte[size];in.readFully(data);int p=0;
            if((head[5]&64)!=0){if(size<4)return tags;p=integer(data,0,version==4)+(version==3?4:0);}
            while(p>=0&&p+10<=size){String id=new String(data,p,4,"ISO-8859-1");int len=integer(data,p+4,version==4);int flags=data[p+9]&255;p+=10;if(len<=0||len>size-p)break;
                if(id.equals("TXXX")&&flags==0){int enc=data[p]&255;String charset=enc==1?"UTF-16":enc==2?"UTF-16BE":enc==3?"UTF-8":"ISO-8859-1";String text=new String(data,p+1,len-1,Charset.forName(charset));int zero=text.indexOf('\0');if(zero>=0)tag(tags,text.substring(0,zero),text.substring(zero+1).replace("\0", "").replace("\uFEFF",""));}
                p+=len;
            }
        }else if(head[0]=='f'&&head[1]=='L'&&head[2]=='a'&&head[3]=='C'){
            skip(in,4);int total=0;boolean last=false;
            while(!last&&total<32*1024*1024){int type=in.readUnsignedByte();last=(type&128)!=0;int size=(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();total+=size;
                if((type&127)!=4||size>1024*1024){skip(in,size);continue;}
                byte[] b=new byte[size];in.readFully(b);if(size<8)continue;int vendor=little(b,0);if(vendor<0||vendor>size-8)continue;int p=4+vendor,count=little(b,p);p+=4;
                for(int i=0;i<count&&p+4<=size;i++){int len=little(b,p);p+=4;if(len<0||len>size-p)break;String text=new String(b,p,len,"UTF-8");p+=len;int split=text.indexOf('=');if(split>0)tag(tags,text.substring(0,split),text.substring(split+1));}
            }
        }
        return tags;
    }
}
