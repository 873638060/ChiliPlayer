package car.salt.player;

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
    static JSONObject read(android.content.Context context,android.net.Uri uri)throws Exception{
        try(InputStream input=SourceAccess.open(context,uri)){
            JSONObject result=read(input);if(result.length()>0)return result;
            if(input instanceof FileInputStream){java.nio.channels.FileChannel channel=((FileInputStream)input).getChannel();
                try{ape(channel,result);if(result.length()==0)mp4(channel,0,channel.size(),0,result);}catch(java.io.IOException notSeekable){}
            }
            return result;
        }
    }
    static byte[] at(java.nio.channels.FileChannel channel,long position,int size)throws IOException{
        if(position<0||size<0||position+size>channel.size())throw new EOFException();
        byte[] bytes=new byte[size];java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes);channel.position(position);while(b.hasRemaining()){if(channel.read(b)<0)throw new EOFException();}return bytes;
    }
    static void ape(java.nio.channels.FileChannel channel,JSONObject tags)throws Exception{
        long end=channel.size();if(end<32)return;
        if(end>=128&&new String(at(channel,end-128,3),"US-ASCII").equals("TAG"))end-=128;
        byte[] footer=at(channel,end-32,32);if(!new String(footer,0,8,"US-ASCII").equals("APETAGEX"))return;
        int size=little(footer,12),count=little(footer,16);if(size<32||size>4*1024*1024||count<0||count>4096)return;
        byte[] data=at(channel,end-size,size-32);int p=0;
        for(int i=0;i<count&&p+8<=data.length;i++){int length=little(data,p),flags=little(data,p+4);p+=8;int start=p;while(p<data.length&&data[p]!=0)p++;if(p==data.length)return;String key=new String(data,start,p-start,"UTF-8");p++;if(length<0||length>data.length-p)return;if((flags&6)==0)tag(tags,key,new String(data,p,length,"UTF-8"));p+=length;}
    }
    static void mp4(java.nio.channels.FileChannel channel,long start,long end,int depth,JSONObject tags)throws Exception{
        if(depth>8)return;int count=0;
        for(long p=start;p+8<=end&&count++<10000;){byte[] header=at(channel,p,8);long length=integer(header,0,false)&0xffffffffL;int prefix=8;String type=new String(header,4,4,"ISO-8859-1");
            if(length==1){byte[] wide=at(channel,p+8,8);length=java.nio.ByteBuffer.wrap(wide).getLong();prefix=16;}else if(length==0)length=end-p;
            if(length<prefix||length>end-p)return;long body=p+prefix,limit=p+length;
            if(type.equals("moov")||type.equals("udta")||type.equals("ilst"))mp4(channel,body,limit,depth+1,tags);
            else if(type.equals("meta")&&body+4<=limit)mp4(channel,body+4,limit,depth+1,tags);
            else if(type.equals("----")&&length<=1024*1024){String name="",value="";for(long q=body;q+8<=limit;){byte[] sub=at(channel,q,8);int n=integer(sub,0,false);if(n<8||n>limit-q)break;String kind=new String(sub,4,4,"US-ASCII");if(kind.equals("name")&&n>=12)name=new String(at(channel,q+12,n-12),"UTF-8");if(kind.equals("data")&&n>=16)value=new String(at(channel,q+16,n-16),"UTF-8");q+=n;}tag(tags,name,value);}
            p=limit;
        }
    }
    static void comments(byte[] data,int p,JSONObject tags)throws Exception{
        if(p+8>data.length)return;int vendor=little(data,p);p+=4;if(vendor<0||vendor>data.length-p-4)return;p+=vendor;int count=little(data,p);p+=4;
        for(int i=0;i<count&&p+4<=data.length;i++){int n=little(data,p);p+=4;if(n<0||n>data.length-p)return;String text=new String(data,p,n,"UTF-8");p+=n;int split=text.indexOf('=');if(split>0)tag(tags,text.substring(0,split),text.substring(split+1));}
    }
    static void ogg(DataInputStream in,JSONObject tags)throws Exception{
        ByteArrayOutputStream packet=new ByteArrayOutputStream();int total=0;
        for(int page=0;page<128&&total<4*1024*1024;page++){byte[] head=new byte[27];in.readFully(head);if(!new String(head,0,4,"US-ASCII").equals("OggS"))return;int count=head[26]&255;byte[] lengths=new byte[count];in.readFully(lengths);
            for(byte raw:lengths){int length=raw&255;byte[] part=new byte[length];in.readFully(part);packet.write(part);total+=length;if(packet.size()>1024*1024)return;if(length<255){byte[] data=packet.toByteArray();packet.reset();if(data.length>=8&&new String(data,0,8,"US-ASCII").equals("OpusTags")){comments(data,8,tags);return;}if(data.length>=7&&data[0]==3&&new String(data,1,6,"US-ASCII").equals("vorbis")){comments(data,7,tags);return;}}}
        }
    }
    static JSONObject read(InputStream source)throws Exception{
        JSONObject tags=new JSONObject();BufferedInputStream buffered=new BufferedInputStream(source);buffered.mark(10);DataInputStream in=new DataInputStream(buffered);
        byte[] head=new byte[10];in.readFully(head);buffered.reset();
        if(head[0]=='O'&&head[1]=='g'&&head[2]=='g'&&head[3]=='S'){ogg(in,tags);return tags;}
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
