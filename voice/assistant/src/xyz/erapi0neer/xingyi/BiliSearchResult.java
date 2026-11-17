package xyz.erapi0neer.xingyi;

import android.util.Xml;
import java.io.StringReader;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Only the requested term and structural result flags leave the local accessibility snapshot. */
final class BiliSearchResult {
    static JSONObject inspect(String snapshot,String query)throws Exception {
        int begin=snapshot.indexOf("<hierarchy"), end=snapshot.lastIndexOf("</hierarchy>");
        if(begin<0 || end<begin)return null;
        XmlPullParser p=Xml.newPullParser();
        p.setInput(new StringReader(snapshot.substring(begin,end+12)));
        boolean term=false,upMatch=false,video=false,all=false,empty=false,network=false,agree=false,legal=false,items=false,guard=false;
        for(int event=p.next();event!=XmlPullParser.END_DOCUMENT;event=p.next()) {
            if(event!=XmlPullParser.START_TAG || !"node".equals(p.getName())
                    || !OfficialAppInstaller.BILIBILI.equals(p.getAttributeValue(null,"package")))continue;
            String text=p.getAttributeValue(null,"text"), id=p.getAttributeValue(null,"resource-id"),desc=p.getAttributeValue(null,"content-desc");
            if(text==null)text=""; if(desc==null)desc=""; if(id==null)id="";
            boolean matches=query.equals(text) || query.equals(desc);
            term|=matches && (id.endsWith("/search_fake_text") || id.endsWith("/search_src_text")
                    || "android.widget.EditText".equals(p.getAttributeValue(null,"class")));
            upMatch|=matches && (id.endsWith("/up_title") || id.endsWith("/upuser"));
            all|="综合".equals(text) || "综合".equals(desc);
            video|="视频".equals(text) || "视频".equals(desc) || "番剧".equals(text) || "番剧".equals(desc);
            String label=text+" "+desc;
            empty|=label.contains("没有找到相关") || label.contains("没有搜到") || label.contains("未找到相关") || label.contains("搜索无结果");
            network|=label.contains("网络异常") || label.contains("网络连接失败") || label.contains("加载失败");
            guard|=label.contains("完成安全验证") || label.contains("请先登录后") || label.contains("请登录后");
            legal|=label.contains("隐私政策") || label.contains("用户协议");
            agree|=text.equals("同意并继续") || desc.equals("同意并继续");
            items|=id.endsWith("/video_title") || id.endsWith("/item_title") || id.endsWith("/card_title") || id.endsWith("/title");
        }
        if(legal && agree)return result(false,"needs_consent","哔哩哔哩正在等待你处理首次启动的用户协议。");
        if(!term)return null;
        if(guard)return result(false,"needs_access","已在哔哩哔哩输入搜索词，客户端要求先登录或完成验证。");
        if(network)return result(false,"network_error","哔哩哔哩没有成功加载搜索结果。");
        if(empty)return result(true,"observed","哔哩哔哩已搜索“"+query+"”，页面显示没有相关结果。");
        if(upMatch)return result(true,"observed","哔哩哔哩已找到同名 UP 主“"+query+"”。");
        if((all && video) || items)return result(true,"observed","哔哩哔哩已打开“"+query+"”的搜索结果页。");
        return null;
    }
    static JSONObject result(boolean ok,String status,String message)throws Exception {
        return new JSONObject().put("ok",ok).put("status",status).put("message",message);
    }
    private BiliSearchResult(){}
}
