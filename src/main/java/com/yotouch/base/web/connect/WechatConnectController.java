package com.yotouch.base.web.connect;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Date;

import me.chanjar.weixin.mp.api.WxMpConfigStorage;
import me.chanjar.weixin.mp.bean.WxMpXmlOutMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import me.chanjar.weixin.common.exception.WxErrorException;
import me.chanjar.weixin.mp.bean.WxMpXmlMessage;
import me.chanjar.weixin.mp.bean.result.WxMpOAuth2AccessToken;
import me.chanjar.weixin.mp.bean.result.WxMpUser;

import com.yotouch.base.service.WechatService;
import com.yotouch.base.service.WechatManager;
import com.yotouch.base.web.controller.BaseController;
import com.yotouch.core.Consts;
import com.yotouch.core.entity.Entity;
import com.yotouch.core.runtime.DbSession;

@Controller
public class WechatConnectController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(WechatConnectController.class);

    @Autowired
    private WechatManager wechatMgr;

    @Value("${wechat.appId:}")
    private String defaultWechatAppId;

    private WechatService getWechatService(String wxUuid) {


        String wechatId = "";

        Entity wechat = this.getDbSession().getEntity("wechat", wxUuid);
        if (wechat == null) {
            wechatId = defaultWechatAppId;
        } else {
            // TODO: 16/5/6 here should read wechat info from db
            wechatId = wechat.v("appId");
        }

        logger.info("Get wechat with wechat uuid " + wechatId);

        WechatService wcService = this.wechatMgr.getService(wechatId);
        return wcService;
    }

    @RequestMapping(value = "/connect/wechat/{uuid}", method = RequestMethod.GET)
    public
    @ResponseBody
    String connect(
            @PathVariable("uuid") String wxUuid,
            @RequestParam(value = "signature", defaultValue = "") String signature,
            @RequestParam(value = "nonce", defaultValue = "") String nonce,
            @RequestParam(value = "timestamp", defaultValue = "") String timestamp,
            @RequestParam(value = "echostr", defaultValue = "") String echostr,
            HttpServletRequest request
    ) {
        WechatService wcService = getWechatService(wxUuid);

        if (wcService.checkSignature(timestamp, nonce, signature)) {
            return echostr;
        }

        return "";
    }

    @RequestMapping(value = "/connect/wechat/{uuid}", method = RequestMethod.POST)
    public void doConnect(
            @PathVariable("uuid") String uuid,
            @RequestParam(value = "signature", defaultValue = "") String signature,
            @RequestParam(value = "nonce", defaultValue = "") String nonce,
            @RequestParam(value = "timestamp", defaultValue = "") String timestamp,
            @RequestParam(value = "encrypt_type", defaultValue = "raw") String encryptType,
            @RequestParam(value = "msg_signature", defaultValue = "") String msgSig,
            @RequestBody String body,
            HttpServletResponse response
    ) throws IOException {

        WechatService wcService = getWechatService(uuid);
        WxMpConfigStorage cfg = wcService.getWechatConfig();
        if (!wcService.checkSignature(timestamp, nonce, signature)) {
            logger.info("WECHAT WRONG");
            response.getWriter().write("Wrong");
        }

        WxMpXmlMessage inMsg = null;
        if ("raw".equals(encryptType)) {
            inMsg = WxMpXmlMessage.fromXml(body);
        } else {
            inMsg = WxMpXmlMessage.fromEncryptedXml(body, cfg, timestamp, nonce, msgSig);
        }

        logger.info("Wechat event \n" +
                "Event " + inMsg.getEvent() + "\n" +
                "EventKey " + inMsg.getEventKey() + "\n" +
                "MessageType " + inMsg.getMsgType()
        );


        //WechatService wxService = getWechatService(uuid);

        WxMpXmlOutMessage outMsg = wcService.route(inMsg);

        logger.info(" Back content " + outMsg);

        if (outMsg == null) {
            return ;
        }

        logger.info(" Back content " + outMsg.toXml());

        String xml = outMsg.toEncryptedXml(wcService.getWechatConfig());


        logger.info(" Back content " + xml);
        logger.info(" Back content " + outMsg.toEncryptedXml(wcService.getWechatConfig()));

        response.setContentType("text/html;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);

        if ("raw".equals(encryptType)) {
            response.getWriter().write(outMsg.toXml());
        } else if ("aes".equals(encryptType)) {
            response.getWriter().write(xml);
        }

        response.flushBuffer();

        return;
    }

    @RequestMapping("/connect/wechat/{uuid}/oauth")
    public String oauth(
            @PathVariable("uuid") String uuid,
            @RequestParam(value="state", defaultValue="") String state,
            @RequestParam(value="backUrl", defaultValue="") String backUrl,
            HttpServletRequest request
    ) {

        WechatService wcService = getWechatService(uuid);

        String fullUrl = webUtil.getBaseUrl(request) + "/connect/wechat/"+uuid+"/oauthCallback?state="+state+"&amp;url=" +backUrl;

        String authUrl = wcService.genAuthUrl(fullUrl, state);
        return "redirect:" + authUrl;
    }

    /*
     * http://app.taomovie.cn/connect/wechat/oauthCallback?
     *  state=S-1455352106593 &
     *  url=http://baidu.com &
     *  code=041ce3d9c65289aa8f7aa710fca64edY&state=S-1455352106593
     */
    @RequestMapping("/connect/wechat/{uuid}/oauthCallback")
    public String oauthCallback(
            @PathVariable("uuid") String uuid,
            @RequestParam(value="url", defaultValue = "") String url,
            @RequestParam(value="state", defaultValue = "") String state,
            @RequestParam(value="code", defaultValue = "") String code,
            Model model,
            HttpServletResponse response,
            HttpServletRequest request
    ) throws WxErrorException, UnsupportedEncodingException {
        WechatService wcService = getWechatService(uuid);

        Entity wechat = wcService.getWechatEntity();

        WxMpOAuth2AccessToken accessToken = wcService.oauth2getAccessToken(code);
        String openId = accessToken.getOpenId();

        DbSession dbSession = this.getDbSession();

        Entity user = dbSession.queryOneRawSql("wechatUser", "openId = ?", new Object[]{openId});

        if (user == null) {
            user = dbSession.newEntity("wechatUser");
            user.setValue("openId", openId);
            user.setValue("status", Consts.STATUS_NORMAL);
            user.setValue("appId", wcService.getWechatEntity().v("appId"));
        }

        user.setValue("accessToken", accessToken.getAccessToken());
        user.setValue("tokenExpires", new Date(accessToken.getExpiresIn() + System.currentTimeMillis()));
        user.setValue("refreshToken", accessToken.getRefreshToken());

        if (!"snsapi_base".equals(wechat.v("oauthScope"))) {
            WxMpUser wxUser = wcService.oauth2getUserInfo(accessToken);
            user = wcService.fillWechatUser(wxUser, user);
        }

        user = dbSession.save(user);

        Cookie c = new Cookie(Consts.COOKIE_NAME_WX_USER_UUID, user.getUuid());
        c.setPath(webUtil.getDefaultCookiePath());
        c.setMaxAge(Integer.MAX_VALUE);
        response.addCookie(c);

        logger.info("WechatUser saved " + user.getUuid());

        url = URLDecoder.decode(url, "utf8");
        logger.info("Redirect " + url);

        if (url.contains("?")) {
            url = url + "&state=" + state;
        } else {
            url = url + "?state=" + state;
        }

        model.addAttribute("toUrl", url);
        return "/common/jsRedirect";
    }


}