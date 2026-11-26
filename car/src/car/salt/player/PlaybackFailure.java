package car.salt.player;

final class PlaybackFailure {
    static String player(int what,int extra,boolean remote){
        if(what==100)return "系统媒体服务异常";
        if(extra==-1010)return "格式限制：系统不支持该编码或编码参数";
        if(extra==-1007)return "媒体数据异常：码流或容器不符合规范，可能损坏";
        if(extra==-110)return "读取或播放准备超时";
        if(extra==-1004)return remote?"网络或服务器音频读取失败":"文件数据读取失败，尚不能判定格式问题";
        if(what==200)return "流式播放兼容限制：容器不适合渐进播放";
        return "播放器未明确报告原因，需结合文件读取与兼容重试记录";
    }
    static String exception(Throwable error,String stage){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof SecurityException)return "文件权限不足或授权失效";
            if(e instanceof java.io.FileNotFoundException)return "文件无法打开：路径、挂载或访问权限异常";
            if(e instanceof java.net.UnknownHostException)return "服务器域名无法解析";
            if(e instanceof java.net.SocketTimeoutException)return "网络读取超时";
            if(e instanceof java.net.ConnectException)return "无法连接服务器";
            if(e instanceof javax.net.ssl.SSLException)return "服务器安全连接失败";
        }
        if("可用存储空间不足".equals(error.getMessage()))return "资源不足：缓存或解码所需存储空间不足";
        if("APE 格式参数超限".equals(error.getMessage()))return "格式限制：APE 解码参数超出当前支持范围";
        if("APE 数据不完整".equals(error.getMessage()))return "媒体数据异常：APE 数据不完整";
        if("软件解码".equals(stage))return "软件解码失败：兼容性、媒体数据或资源问题，尚未确定";
        if("兼容文件复制".equals(stage))return "文件读取或缓存写入失败，尚不能判定格式问题";
        return "数据源打开或播放器初始化失败，尚未确定原因";
    }
}
