package car.salt.soft;

import android.content.Context;
import java.io.*;
import java.util.concurrent.Callable;
import davaguine.jmac.decoder.IAPEDecompress;
import davaguine.jmac.info.APEInfo;
import davaguine.jmac.info.WaveHeader;

/** Decode only when the device cannot open APE itself; the resulting PCM is seekable. */
final class ApeSupport {
    static File decodeIfNeeded(Context context,File source,Callable<Boolean> cancelled)throws Exception {
        try(InputStream in=new FileInputStream(source)){
            if(in.read()!='M'||in.read()!='A'||in.read()!='C'||in.read()!=' ')return source;
        }
        File output=File.createTempFile("playing-",".wav",context.getCacheDir());
        davaguine.jmac.tools.RandomAccessFile input=null;boolean complete=false;
        try{
            input=new davaguine.jmac.tools.RandomAccessFile(source,"r");
            IAPEDecompress decoder=IAPEDecompress.CreateIAPEDecompressCore(new APEInfo(input),-1,-1);
            int align=decoder.getApeInfoBlockAlign(),blocks=decoder.getApeInfoDecompressTotalBlocks();
            long size=(long)blocks*align;
            if(align<1||align>32||blocks<1||size>Integer.MAX_VALUE-44)throw new IOException("APE 格式参数超限");
            if(size+8*1024*1024>output.getUsableSpace())throw new IOException("可用存储空间不足");
            WaveHeader header=new WaveHeader();WaveHeader.FillWaveHeader(header,(int)size,decoder.getApeInfoWaveFormatEx(),0);
            Journal.event(context,"开始 APE 软件解码，输出 "+size+" 字节");
            try(OutputStream out=new BufferedOutputStream(new FileOutputStream(output),65536)){
                out.write(header.write());byte[] buffer=new byte[4096*align];int remaining=blocks;
                while(remaining>0){
                    if(cancelled.call())throw new InterruptedIOException();
                    if(output.getUsableSpace()<4*1024*1024)throw new IOException("可用存储空间不足");
                    int decoded=decoder.GetData(buffer,Math.min(4096,remaining));
                    if(decoded<=0||decoded>remaining)throw new IOException("APE 数据不完整");
                    out.write(buffer,0,decoded*align);remaining-=decoded;
                }
            }
            complete=true;Journal.event(context,"APE 软件解码完成");return output;
        }finally{if(input!=null)input.close();if(!complete)output.delete();}
    }
}
