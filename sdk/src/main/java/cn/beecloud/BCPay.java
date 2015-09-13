/**
 * BCPay.java
 *
 * Created by xuanzhui on 2015/7/27.
 * Copyright (c) 2015 BeeCloud. All rights reserved.
 */
package cn.beecloud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;

import com.alipay.sdk.app.PayTask;
import com.baidu.android.pay.PayCallBack;
import com.baidu.paysdk.PayCallBackManager;
import com.baidu.paysdk.api.BaiduPay;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.tencent.mm.sdk.constants.Build;
import com.tencent.mm.sdk.modelpay.PayReq;
import com.tencent.mm.sdk.openapi.IWXAPI;
import com.tencent.mm.sdk.openapi.WXAPIFactory;

import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.beecloud.async.BCCallback;
import cn.beecloud.entity.BCPayReqParams;
import cn.beecloud.entity.BCPayResult;
import cn.beecloud.entity.BCQRCodeResult;
import cn.beecloud.entity.BCReqParams;
import cn.beecloud.entity.BCRestfulCommonResult;

/**
 * 支付类
 * 单例模式
 */
public class BCPay {
    private static final String TAG = "BCPay";

    /**
     * 保留callback实例
     */
    public static BCCallback payCallback;

    private static Activity mContextActivity;

    // IWXAPI 是第三方app和微信通信的openapi接口
    public static IWXAPI wxAPI = null;

    private static BCPay instance;

    private BCPay() {}

    /**
     * 唯一获取BCPay实例的入口
     * @param context   留存context
     * @return          BCPay实例
     */
    public synchronized static BCPay getInstance(Context context) {
        mContextActivity = (Activity)context;
        if (instance == null) {
            instance = new BCPay();
            payCallback = null;
        }
        return instance;
    }

    static Activity getContextActivity() {
        return mContextActivity;
    }

    /**
     * 初始化微信支付，必须在需要调起微信支付的Activity的onCreate函数中调用，例如：
     * BCPay.initWechatPay(XXActivity.this);
     * 微信支付只有经过初始化才能成功调起，其他支付渠道无此要求。
     *
     * @param context      需要在某Activity里初始化微信支付，此参数需要传递该Activity.this，不能为null
     * @return             返回出错信息，如果成功则为null
     */
    public static String initWechatPay(Context context, String wechatAppID) {
        String errMsg = null;

        if (context == null) {
            errMsg = "Error: initWechatPay里，context参数不能为null.";
            Log.e(TAG, errMsg);
            return errMsg;
        }

        if (wechatAppID == null || wechatAppID.length() == 0) {
            errMsg = "Error: initWechatPay里，wx_appid必须为合法的微信AppID.";
            Log.e(TAG, errMsg);
            return errMsg;
        }

        // 通过WXAPIFactory工厂，获取IWXAPI的实例
        wxAPI = WXAPIFactory.createWXAPI(context, null);

        BCCache.getInstance(null).wxAppId = wechatAppID;

        try {
            if (isWXPaySupported()) {
                // 将该app注册到微信
                wxAPI.registerApp(wechatAppID);
            } else {
                errMsg = "Error: 安装的微信版本不支持支付.";
                Log.d(TAG, errMsg);
            }
        } catch (Exception ignored) {
            errMsg = "Error: 无法注册微信 " + wechatAppID + ". Exception: " + ignored.getMessage();
            Log.e(TAG, errMsg);
        }

        return errMsg;
    }

    public enum PAYPAL_PAY_TYPE {
        SANDBOX, LIVE
    }

    /**
     * 初始化paypal, 仅在需要paypal支付的时候需要调用
     * @param clientId  PayPal APP Client ID
     * @param secret    PayPal APP Secret
     * @param type      paypay pay type, SANDBOX for test before online, LIVE is for online
     * @param retrieveShippingAddresses set true then it will enable PayPal Shipping Addresses Retrieval, but it sometimes may cause 'shipping address invalid' error during payment
     */
    public static void initPayPal(String clientId, String secret, PAYPAL_PAY_TYPE type, Boolean retrieveShippingAddresses){
        BCCache instance = BCCache.getInstance(null);
        instance.paypalClientID = clientId;
        instance.paypalSecret = secret;
        instance.paypalPayType = type;
        instance.retrieveShippingAddresses = retrieveShippingAddresses;
    }

    /**
     * 判断微信是否支持支付
     * @return true表示支持
     */
    public static boolean isWXPaySupported() {
        boolean isPaySupported = false;
        if (wxAPI != null) {
            isPaySupported = wxAPI.getWXAppSupportAPI() >= Build.PAY_SUPPORTED_SDK_INT;
        }
        return isPaySupported;
    }

    /**
     * 判断微信客户端是否安装并被支持
     * @return true表示支持
     */
    public static boolean isWXAppInstalledAndSupported() {
        boolean isWXAppInstalledAndSupported = false;
        if (wxAPI != null) {
            isWXAppInstalledAndSupported = wxAPI.isWXAppInstalled() && wxAPI.isWXAppSupportAPI();
        }
        return isWXAppInstalledAndSupported;
    }

    /**
     * 校验bill参数
     * 设置公用参数
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param parameters      用于存储公用信息
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @return                返回校验失败信息, 为null则表明校验通过
     */
    private String prepareParametersForPay(final String billTitle, final Integer billTotalFee,
                                           final String billNum, final Map<String, String> optional,
                                           BCPayReqParams parameters) {

        if (!BCValidationUtil.isValidBillTitleLength(billTitle)) {
            return "parameters: 不合法的参数-订单标题长度不合法, 32个字节内, 汉字以2个字节计";
        }

        if (!BCValidationUtil.isValidBillNum(billNum))
            return "parameters: 订单号必须是长度8~32位字母和/或数字组合成的字符串";

        if (billTotalFee < 0) {
            return "parameters: billTotalFee " + billTotalFee +
                    " 格式不正确, 必须是以分为单位的正整数, 比如100表示1元";
        }

        parameters.title = billTitle;
        parameters.totalFee = billTotalFee;
        parameters.billNum = billNum;
        parameters.optional = optional;

        return null;
    }

    /**
     * 支付调用总接口
     *
     * @param channelType     支付类型  对于支付手机APP端目前只支持WX_APP, ALI_APP, UN_APP
     *                        @see cn.beecloud.entity.BCReqParams.BCChannelTypes
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    private void reqPaymentAsync(final BCReqParams.BCChannelTypes channelType,
                                 final String billTitle, final Integer billTotalFee,
                                 final String billNum,final Map<String, String> optional,
                                 final BCCallback callback) {

        if (callback == null) {
            Log.w(TAG, "请初始化callback");
            return;
        }

        payCallback = callback;

        BCCache.executorService.execute(new Runnable() {
            @Override
            public void run() {

                //校验并准备公用参数
                BCPayReqParams parameters;
                try {
                    parameters = new BCPayReqParams(channelType);
                } catch (BCException e) {
                    callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_EXCEPTION,
                            e.getMessage()));
                    return;
                }

                String paramValidRes = prepareParametersForPay(billTitle, billTotalFee,
                        billNum, optional, parameters);

                if (paramValidRes != null) {
                    callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_INVALID_PARAMS,
                            paramValidRes));
                    return;
                }

                parameters.billTimeout = 120;

                String payURL = BCHttpClientUtil.getBillPayURL();

                HttpResponse response = BCHttpClientUtil.httpPost(payURL, parameters.transToBillReqMapParams());
                if (null == response) {
                    callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_NETWORK_ISSUE,
                            "Network Error"));
                    return;
                }
                if (response.getStatusLine().getStatusCode() == 200) {
                    String ret;
                    try {
                        ret = EntityUtils.toString(response.getEntity(), "UTF-8");

                        //反序列化json串
                        Gson res = new Gson();

                        Type type = new TypeToken<Map<String,Object>>() {}.getType();
                        Map<String, Object> responseMap = res.fromJson(ret, type);

                        //判断后台返回结果
                        Double resultCode = (Double) responseMap.get("result_code");
                        if (resultCode == 0) {

                            if (mContextActivity != null) {

                                //针对不同的支付渠道调用不同的API
                                switch (channelType){
                                    case WX_APP:
                                        reqWXPaymentViaAPP(responseMap);
                                        break;
                                    case ALI_APP:
                                        reqAliPaymentViaAPP(responseMap);
                                        break;
                                    case UN_APP:
                                        reqUnionPaymentViaAPP(responseMap);
                                        break;
                                    case BD_APP:
                                        reqBaiduPaymentViaAPP(responseMap);
                                        break;
                                    default:
                                        callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_INVALID_PARAMS,
                                                "channelType参数不合法"));
                                }

                            } else {
                                callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_EXCEPTION,
                                        "Context-Activity Exception in reqAliPayment"));
                            }
                        } else {
                            //返回后端传回的错误信息
                            callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_ERR_FROM_SERVER,
                                    String.valueOf(responseMap.get("result_msg")) + ", " +
                                            String.valueOf(responseMap.get("err_detail"))));
                        }

                    } catch (IOException e) {
                        callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_NETWORK_ISSUE,
                                "Invalid Response"));
                    }
                } else {
                    callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_NETWORK_ISSUE,
                            "Network Error"));
                }

            }
        });
    }

    /**
     * 与服务器交互后下一步进入微信app支付
     *
     * @param responseMap     服务端返回参数
     */
    private void reqWXPaymentViaAPP(final Map<String, Object> responseMap) {

        //获取到服务器的订单参数后，以下主要代码即可调起微信支付。
        PayReq request = new PayReq();
        request.appId = String.valueOf(responseMap.get("app_id"));
        request.partnerId = String.valueOf(responseMap.get("partner_id"));
        request.prepayId = String.valueOf(responseMap.get("prepay_id"));
        request.packageValue = String.valueOf(responseMap.get("package"));
        request.nonceStr = String.valueOf(responseMap.get("nonce_str"));
        request.timeStamp = String.valueOf(responseMap.get("timestamp"));
        request.sign = String.valueOf(responseMap.get("pay_sign"));

        if (wxAPI != null) {
            wxAPI.sendReq(request);
        } else {
            payCallback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_EXCEPTION,
                    "Error: 微信API为空, 请确认已经在需要调起微信支付的Activity的onCreate函数中调用BCPay.initWechatPay(XXActivity.this)"));
        }
    }

    /**
     * 与服务器交互后下一步进入支付宝app支付
     *
     * @param responseMap     服务端返回参数
     */
    private void reqAliPaymentViaAPP(final Map<String, Object> responseMap) {

        String orderString = (String) responseMap.get("order_string");

        PayTask aliPay = new PayTask(mContextActivity);
        String aliResult = aliPay.pay(orderString);

        //解析ali返回结果
        Pattern pattern = Pattern.compile("resultStatus=\\{(\\d+?)\\}");
        Matcher matcher = pattern.matcher(aliResult);
        String resCode = "";
        if (matcher.find())
            resCode = matcher.group(1);

        String result;
        String errMsg;

        //9000-订单支付成功, 8000-正在处理中, 4000-订单支付失败, 6001-用户中途取消, 6002-网络连接出错
        String errDetail;
        if (resCode.equals("9000")) {
            result = BCPayResult.RESULT_SUCCESS;
            errMsg = BCPayResult.RESULT_SUCCESS;
            errDetail = errMsg;
        } else if (resCode.equals("6001")) {
            result = BCPayResult.RESULT_CANCEL;
            errMsg = BCPayResult.RESULT_CANCEL;
            errDetail = errMsg;
        } else if (resCode.equals("8000")) {
            result = BCPayResult.RESULT_UNKNOWN;
            errMsg = BCPayResult.RESULT_PAYING_UNCONFIRMED;
            errDetail = "订单正在处理中，无法获取成功确认信息";
        } else {
            result = BCPayResult.RESULT_FAIL;
            errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;

            if (resCode.equals("4000"))
                errDetail = "订单支付失败";
            else
                errDetail = "网络连接出错";
        }

        payCallback.done(new BCPayResult(result, errMsg, errDetail));
    }

    /**
     * 与服务器交互后下一步进入银联app支付
     *
     * @param responseMap     服务端返回参数
     */
    private void reqUnionPaymentViaAPP(final Map<String, Object> responseMap) {

        String TN = (String) responseMap.get("tn");

        Intent intent = new Intent();
        intent.setClass(mContextActivity, BCUnionPaymentActivity.class);
        intent.putExtra("tn", TN);
        mContextActivity.startActivity(intent);
    }

    /**
     * 与服务器交互后下一步进入百度app支付
     *
     * @param responseMap     服务端返回参数
     */
    private void reqBaiduPaymentViaAPP(final Map<String, Object> responseMap) {
        String orderInfo = (String) responseMap.get("orderInfo");

        Log.w(TAG, orderInfo);

        Map<String, String> map = new HashMap<String, String>();
        BaiduPay.getInstance().doPay(mContextActivity, orderInfo, new PayCallBack() {
            public void onPayResult(int stateCode, String payDesc) {
                Log.d(TAG, "rsult=" + stateCode + "#desc=" + payDesc);

                String result;
                String errMsg;
                String errDetail;

                switch (stateCode) {
                    case PayCallBackManager.PayStateModle.PAY_STATUS_SUCCESS:// 需要到服务端验证支付结果
                        result = BCPayResult.RESULT_SUCCESS;
                        errMsg = BCPayResult.RESULT_SUCCESS;
                        errDetail = errMsg;
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_PAYING:// 需要到服务端验证支付结果
                        result = BCPayResult.RESULT_UNKNOWN;
                        errMsg = BCPayResult.RESULT_PAYING_UNCONFIRMED;
                        errDetail = "订单正在处理中，无法获取成功确认信息";
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_CANCEL:
                        result = BCPayResult.RESULT_CANCEL;
                        errDetail = errMsg = BCPayResult.RESULT_CANCEL;
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_NOSUPPORT:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "不支持该种支付方式";
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_TOKEN_INVALID:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "无效的登陆状态";
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_LOGIN_ERROR:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "登陆失败";
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_ERROR:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "支付失败";
                        break;
                    case PayCallBackManager.PayStateModle.PAY_STATUS_LOGIN_OUT:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "退出登录";
                        break;
                    default:
                        result = BCPayResult.RESULT_FAIL;
                        errMsg = BCPayResult.FAIL_ERR_FROM_CHANNEL;
                        errDetail = "支付失败";
                        break;
                }

                payCallback.done(new BCPayResult(result, errMsg, errDetail+"#result=" + stateCode + "#desc=" + payDesc));
            }

            public boolean isHideLoadingDialog() {
                return true;
            }
        }, map);

    }

    /**
     * 微信支付调用接口
     * 如果您申请的是新版本(V3)的微信支付，请使用此接口发起微信支付.
     * 您在BeeCloud控制台需要填写“微信Partner ID”、“微信Partner KEY”、“微信APP ID”.
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    public void reqWXPaymentAsync(final String billTitle, final Integer billTotalFee,
                                  final String billNum,final Map<String, String> optional,
                                  final BCCallback callback) {
        this.reqPaymentAsync(BCReqParams.BCChannelTypes.WX_APP, billTitle, billTotalFee,
                billNum, optional, callback);
    }

    /**
     * 支付宝支付
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    public void reqAliPaymentAsync(final String billTitle, final Integer billTotalFee,
                                   final String billNum,final Map<String, String> optional,
                                   final BCCallback callback) {
        this.reqPaymentAsync(BCReqParams.BCChannelTypes.ALI_APP, billTitle, billTotalFee,
                billNum, optional, callback);
    }

    /**
     * 百度钱包支付
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    public void reqBaiduPaymentAsync(final String billTitle, final Integer billTotalFee,
                                   final String billNum,final Map<String, String> optional,
                                   final BCCallback callback) {
        this.reqPaymentAsync(BCReqParams.BCChannelTypes.BD_APP, billTitle, billTotalFee,
                billNum, optional, callback);
    }

    /**
     * PayPal支付
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param optional        为扩展参数HashMap，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    public void reqPayPalPaymentAsync(final String billTitle, final Integer billTotalFee,
                                          final HashMap<String, String> optional,
                                          final BCCallback callback) {

        payCallback = callback;

        if (BCCache.getInstance(null).paypalClientID == null ||
                BCCache.getInstance(null).paypalSecret == null ||
                BCCache.getInstance(null).paypalPayType == null) {
            callback.done(new BCPayResult(BCPayResult.RESULT_FAIL, BCPayResult.FAIL_INVALID_PARAMS,
                    "使用PayPal支付需要设置client id，PayPal应用secret和PayPal支付类型"));
            return;
        }

        Intent intent = new Intent(mContextActivity, BCPayPalPaymentActivity.class);
        intent.putExtra("billTitle", billTitle);
        intent.putExtra("billTotalFee", billTotalFee);
        Gson gson = new Gson();
        intent.putExtra("optional", gson.toJson(optional));
        mContextActivity.startActivity(intent);

    }

    private String getPayPalAccessToken() {
        HttpResponse response = BCHttpClientUtil.getPayPalAccessToken();

        if (response == null)
            return null;

        String accessToken = null;

        if (response.getStatusLine().getStatusCode() == 200) {
            String ret;
            try {
                ret = EntityUtils.toString(response.getEntity(), "UTF-8");

                //反序列化json
                Gson res = new Gson();

                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> responseMap = res.fromJson(ret, type);

                //判断后台返回结果
                accessToken = String.valueOf(responseMap.get("access_token"));
            } catch (IOException e) {
                Log.e("BCPayPalPaymentActivity", e.getMessage());
            }

        }

        return accessToken;
    }

    /**
     * sync with server to verify the payment
     *
     * @return BCPayResult.RESULT_SUCCESS means sync successfully and payment is valid
     */
    String syncPayPalPayment(final String billTitle, final Integer billTotalFee, final String billNum,
                                    final String optional, final PAYPAL_PAY_TYPE paypalType, final String token) {

        //verify params
        BCPayReqParams parameters;
        try {
            parameters = new BCPayReqParams(paypalType == PAYPAL_PAY_TYPE.LIVE ?
                    BCReqParams.BCChannelTypes.PAYPAL_LIVE : BCReqParams.BCChannelTypes.PAYPAL_SANDBOX);
        } catch (BCException e) {
            return e.getMessage();
        }

        Map<String, String> optionalMap = null;

        if (optional != null) {
            Gson gson = new Gson();
            optionalMap = gson.fromJson(optional, new TypeToken<Map<String,Object>>() {}.getType());
        }

        String paramValidRes = prepareParametersForPay(billTitle, billTotalFee,
                billNum, optionalMap, parameters);

        if (paramValidRes != null) {
            return paramValidRes;
        }

        String accessToken = token;

        if (accessToken == null)
            accessToken = getPayPalAccessToken();

        //Log.w("BCPay", accessToken);

        if (accessToken == null)
            return "Can't get access Token";

        parameters.currency="USD";
        parameters.accessToken = "Bearer " + accessToken;

        String payURL = BCHttpClientUtil.getBillPayURL();

        HttpResponse response = BCHttpClientUtil.httpPost(payURL, parameters.transToBillReqMapParams());
        if (null == response) {
            return "Network Error";
        }
        if (response.getStatusLine().getStatusCode() == 200) {
            String ret;
            try {
                ret = EntityUtils.toString(response.getEntity(), "UTF-8");

                Gson res = new Gson();

                Type type = new TypeToken<Map<String,Object>>() {}.getType();
                Map<String, Object> responseMap = res.fromJson(ret, type);

                //check result
                Double resultCode = (Double) responseMap.get("result_code");
                //String errDetail = (String)responseMap.get("err_detail");

                if (resultCode == 0) {
                    return BCPayResult.RESULT_SUCCESS;
                /*
                } else if (resultCode == 7 && errDetail != null && errDetail.startsWith("PAYPAL")) {

                    Log.w("BCPay", "not a valid payment: " + errDetail);

                    //here RESULT_SUCCESS only means sync success, not means successful pay
                    //for example, you paid 1 USD, but the PayPal server just received 0.1 USD
                    //then it is not a valid payment, it is rare but to prevent fraud

                    return BCPayResult.RESULT_SUCCESS;
                */
                } else {
                    //return error info from server
                    return String.valueOf(responseMap.get("result_msg")) + " - " +
                                    String.valueOf(responseMap.get("err_detail"));
                }

            } catch (IOException e) {
                return "Invalid Response";
            }
        } else {
            return "Network Error";
        }
    }

    /**
     * sync with server to verify the payment
     *
     * @return BCPayResult.RESULT_SUCCESS means sync successfully and payment is valid
     */
    public String syncPayPalPayment(final String syncJson, final String token) {
        Gson gson = new Gson();
        Map<String, String> syncItem = gson.fromJson(syncJson, new TypeToken<Map<String,String>>() {}.getType());

        Integer billTotalFee;
        try{
            billTotalFee = Integer.valueOf(syncItem.get("billTotalFee"));
        } catch (Exception e){
            Log.e(TAG, e.getMessage());
            billTotalFee = -1;
        }

        String result = syncPayPalPayment(syncItem.get("billTitle"), billTotalFee,
                syncItem.get("billNum"), syncItem.get("optional"),
                PAYPAL_PAY_TYPE.valueOf(syncItem.get("channel")), token);

        if (result.equals(BCPayResult.RESULT_SUCCESS)) {
            List<String> syncedRecord = new ArrayList<String>(1);
            syncedRecord.add(syncJson);
            BCCache.getInstance(mContextActivity).removeSyncedPalPalRecords(syncedRecord);
        }

        return result;
    }

    /**
     * batch sync PayPal local payments
     *
     * @return Map use key "cachedNum" to get the cached total number, use key "syncedNum" to get the successfully synced number(also payments are valid)
     */
    public Map<String, Integer> batchSyncPayPalPayment() {
        List<String> allRecords = BCCache.getInstance(mContextActivity).getUnSyncedPayPalRecords();

        Map<String, Integer> result = new HashMap<String, Integer>();
        result.put("cachedNum", allRecords.size());

        String accessToken = getPayPalAccessToken();

        List<String> syncedRecords = new ArrayList<String>();

        for (String jsonStr : allRecords) {
            if (syncPayPalPayment(jsonStr, accessToken).equals(BCPayResult.RESULT_SUCCESS))
                syncedRecords.add(jsonStr);
        }

        result.put("syncedNum", syncedRecords.size());

        BCCache.getInstance(mContextActivity).removeSyncedPalPalRecords(syncedRecords);

        return result;
    }

    /**
     * 银联在线支付
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param callback        支付完成后的回调函数
     */
    public void reqUnionPaymentAsync(final String billTitle, final Integer billTotalFee,
                                     final String billNum,final Map<String, String> optional,
                                     final BCCallback callback) {
        this.reqPaymentAsync(BCReqParams.BCChannelTypes.UN_APP, billTitle, billTotalFee,
                billNum, optional, callback);
    }

    /**
     * 将string转化成对应的bitmap
     *
     * @param contentsToEncode          原始字符串
     * @param imageWidth                生成的图片宽度, 以px为单位
     * @param imageHeight               生成的图片高度, 以px为单位
     * @param marginSize                生成的图片中二维码到图片边缘的留边
     * @param color                     二维码图片的前景色
     * @param colorBack                 二维码图片的背景色
     * @return bitmap                   QR Code图片
     * @throws WriterException          zxing无法生成QR Code
     * @throws IllegalStateException    本函数不应该在UI主进程调用, 通过使用AsyncTask或者新建进程
     */
    public static Bitmap generateBitmap(String contentsToEncode,
                                        int imageWidth, int imageHeight,
                                        int marginSize, int color, int colorBack)
            throws WriterException, IllegalStateException {

        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Should not be invoked from the UI thread");
        }

        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, marginSize);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        MultiFormatWriter writer = new MultiFormatWriter();
        BitMatrix result = writer.encode(contentsToEncode, BarcodeFormat.QR_CODE, imageWidth, imageHeight, hints);

        final int width = result.getWidth();
        final int height = result.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = result.get(x, y) ? color : colorBack;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    /**
     * 生成二维码总接口
     *
     * @param channelType     生成扫码的类型  对于支付手机APP端目前只支持WX_NATIVE, ALI_QRCODE, ALI_OFFLINE_QRCODE
     *                        @see cn.beecloud.entity.BCReqParams.BCChannelTypes
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param genQRCode       是否生成QRCode Bitmap
     * @param qrCodeWidth     如果生成, QRCode的宽度, null则使用默认参数
     * @param qrPayMode       支付宝内嵌二维码支付(ALI_QRCODE)的选填参数
     *                        @see BCPayReqParams
     * @param returnUrl       同步返回页面, ALI_QRCODE时为必填
     * @param callback        支付完成后的回调函数
     */
    private void reqQRCodeAsync(final BCReqParams.BCChannelTypes channelType,
                                final String billTitle, final Integer billTotalFee,
                                final String billNum, final Map<String, String> optional,
                                final Boolean genQRCode, final Integer qrCodeWidth,
                                final String qrPayMode, final String returnUrl,
                                final BCCallback callback) {

        if (callback == null) {
            Log.w(TAG, "请初始化callback");
            return;
        }

        BCCache.executorService.execute(new Runnable() {
            @Override
            public void run() {

                //校验并准备公用参数
                BCPayReqParams parameters;
                try {
                    parameters = new BCPayReqParams(channelType, BCReqParams.ReqType.QRCODE);
                } catch (BCException e) {
                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                            BCRestfulCommonResult.APP_INNER_FAIL, e.getMessage()));
                    return;
                }

                String paramValidRes = prepareParametersForPay(billTitle, billTotalFee,
                        billNum, optional, parameters);

                if (paramValidRes != null) {
                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                            BCRestfulCommonResult.APP_INNER_FAIL,
                            paramValidRes));
                    return;
                }

                //添加ALI_QRCODE参数
                if (channelType == BCReqParams.BCChannelTypes.ALI_QRCODE){
                    if (returnUrl == null || !BCValidationUtil.isStringValidURL(returnUrl)){
                        callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                                BCRestfulCommonResult.APP_INNER_FAIL,
                                "returnUrl为ALI_QRCODE的必填参数，并且需要以http://或https://开始"));
                        return;
                    }

                    parameters.returnUrl = returnUrl;
                    parameters.qrPayMode = qrPayMode;
                }

                String qrCodeReqURL = BCHttpClientUtil.getQRCodeReqURL();

                HttpResponse response = BCHttpClientUtil.httpPost(qrCodeReqURL, parameters.transToBillReqMapParams());
                if (null == response) {
                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                            BCRestfulCommonResult.APP_INNER_FAIL,
                            "Network Error"));
                    return;
                }
                if (response.getStatusLine().getStatusCode() == 200) {
                    String ret;
                    try {
                        ret = EntityUtils.toString(response.getEntity(), "UTF-8");

                        //反序列化json
                        Gson res = new Gson();

                        Type type = new TypeToken<Map<String,Object>>() {}.getType();
                        Map<String, Object> responseMap = res.fromJson(ret, type);

                        //判断后台返回结果
                        Integer resultCode = ((Double) responseMap.get("result_code")).intValue();
                        if (resultCode == 0) {

                            String content = null;
                            Bitmap qrBitmap = null;
                            String aliQRCodeHtml = null;
                            int imgSize = BCQRCodeResult.DEFAULT_QRCODE_WIDTH;

                            //针对不同的支付渠道获取不同的参数
                            switch (channelType){
                                case WX_NATIVE:
                                    if (responseMap.get("code_url") != null) {
                                        content = (String) responseMap.get("code_url");
                                    }
                                    break;
                                case ALI_QRCODE:
                                    if (responseMap.get("url") != null) {
                                        content = (String) responseMap.get("url");
                                    }
                                    aliQRCodeHtml = String.valueOf(responseMap.get("html"));
                                    break;
                                case ALI_OFFLINE_QRCODE:
                                    if (responseMap.get("qr_code") != null) {
                                        content = (String) responseMap.get("qr_code");
                                    }
                                    break;
                                default:
                                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                                            BCRestfulCommonResult.APP_INNER_FAIL,
                                            "channelType参数不合法"));
                            }

                            if (genQRCode && content != null) {

                                if (qrCodeWidth != null)
                                    imgSize = qrCodeWidth;

                                try {
                                    qrBitmap = BCPay.generateBitmap(content, imgSize,
                                            imgSize, 0,
                                            Color.BLACK, Color.WHITE);
                                } catch (WriterException e) {
                                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                                            BCRestfulCommonResult.APP_INNER_FAIL, e.getMessage()));
                                    return;
                                }
                            }

                            callback.done(new BCQRCodeResult(resultCode,
                                    String.valueOf(responseMap.get("result_msg")),
                                    String.valueOf(responseMap.get("err_detail")),
                                    imgSize, imgSize,
                                    content, qrBitmap,
                                    aliQRCodeHtml));

                        } else {
                            //返回服务端传回的错误信息
                            callback.done(new BCQRCodeResult(resultCode,
                                    String.valueOf(responseMap.get("result_msg")),
                                    String.valueOf(responseMap.get("err_detail"))));
                        }

                    } catch (IOException e) {
                        callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                                BCRestfulCommonResult.APP_INNER_FAIL, e.getMessage()));
                    }
                } else {
                    callback.done(new BCQRCodeResult(BCRestfulCommonResult.APP_INNER_FAIL_NUM,
                            BCRestfulCommonResult.APP_INNER_FAIL,
                            "Network Error"));
                }

            }
        });
    }

    /**
     * 生成微信支付二维码
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param genQRCode       是否生成QRCode Bitmap
     * @param qrCodeWidth     如果生成, QRCode的宽度, null则使用默认参数
     * @param callback        支付完成后的回调函数
     */
    public void reqWXQRCodeAsync(final String billTitle, final Integer billTotalFee,
                                final String billNum, final Map<String, String> optional,
                                final Boolean genQRCode, final Integer qrCodeWidth,
                                final BCCallback callback) {
        reqQRCodeAsync(BCReqParams.BCChannelTypes.WX_NATIVE, billTitle, billTotalFee,
                billNum, optional, genQRCode, qrCodeWidth, null, null, callback);
    }

    /**
     * 生成支付宝内嵌支付二维码
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param returnUrl       同步返回页面, 必填
     * @param qrPayMode       支付宝内嵌二维码类型, 可为null
     *                        @see BCPayReqParams
     * @param callback        支付完成后的回调函数
     */
    public void reqAliQRCodeAsync(final String billTitle, final Integer billTotalFee,
                                  final String billNum, final Map<String, String> optional,
                                  final String returnUrl,
                                  final String qrPayMode,
                                  final BCCallback callback) {
        reqQRCodeAsync(BCReqParams.BCChannelTypes.ALI_QRCODE, billTitle, billTotalFee,
                billNum, optional, false, null, qrPayMode, returnUrl, callback);
    }

    /**
     * 生成支付宝线下支付二维码
     *
     * @param billTitle       商品描述, 32个字节内, 汉字以2个字节计
     * @param billTotalFee    支付金额，以分为单位，必须是正整数
     * @param billNum         商户自定义订单号
     * @param optional        为扩展参数，可以传入任意数量的key/value对来补充对业务逻辑的需求
     * @param genQRCode       是否生成QRCode Bitmap
     * @param qrCodeWidth     如果生成, QRCode的宽度, null则使用默认参数
     * @param callback        支付完成后的回调函数
    */
    public void reqAliOfflineQRCodeAsync(final String billTitle, final Integer billTotalFee,
                                 final String billNum, final Map<String, String> optional,
                                 final Boolean genQRCode, final Integer qrCodeWidth,
                                 final BCCallback callback) {
        reqQRCodeAsync(BCReqParams.BCChannelTypes.ALI_OFFLINE_QRCODE, billTitle, billTotalFee,
                billNum, optional, genQRCode, qrCodeWidth, null, null, callback);
    }
}
