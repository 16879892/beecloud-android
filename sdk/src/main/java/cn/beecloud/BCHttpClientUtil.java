/**
 * BCHttpClientUtil.java
 *
 * Created by xuanzhui on 2015/7/27.
 * Copyright (c) 2015 BeeCloud. All rights reserved.
 */
package cn.beecloud;

import android.util.Log;

import com.google.gson.Gson;

import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.KeyStore;
import java.util.Map;
import java.util.Random;

/**
 * 网络请求工具类
 */
class BCHttpClientUtil {

    //主机地址
//    private static final String[] BEECLOUD_HOSTS = {"https://apibj.beecloud.cn",
//            "https://apisz.beecloud.cn",
//            "https://apiqd.beecloud.cn",
//            "https://apihz.beecloud.cn"
//    };
    private static final String[] BEECLOUD_HOSTS = {
            "https://apigk2.beecloud.cn"
    };

    //Rest API版本号
    private static final String HOST_API_VERSION = "/1/";

    //订单支付部分URL 和 获取扫码信息
    private static final String BILL_PAY_URL = "rest/bill";

    //支付订单查询部分URL
    private static final String BILL_QUERY_URL = "rest/bills?para=";

    //退款订单查询部分URL
    private static final String REFUND_QUERY_URL = "rest/refunds?para=";

    //退款订单查询部分URL
    private static final String REFUND_STATUS_QUERY_URL = "rest/refund/status?para=";

    private final static String PAYPAL_LIVE_BASE_URL = "https://api.paypal.com/v1/";
    private final static String PAYPAL_SANDBOX_BASE_URL = "https://api.sandbox.paypal.com/v1/";

    private final static String PAYPAL_ACCESS_TOKEN_URL= "oauth2/token";

    /**
     * 随机获取主机, 并加入API版本号
     */
    private static String getRandomHost() {
        Random r = new Random();
        return BEECLOUD_HOSTS[r.nextInt(BEECLOUD_HOSTS.length)] + HOST_API_VERSION;
    }

    /**
     * @return  支付请求URL
     */
    public static String getBillPayURL() {
        return getRandomHost() + BILL_PAY_URL;
    }

    /**
     * @return  获取扫码信息URL
     */
    public static String getQRCodeReqURL() {
        return getRandomHost() + BILL_PAY_URL;
    }

    /**
     * @return  查询支付订单URL
     */
    public static String getBillQueryURL() {
        return getRandomHost() + BILL_QUERY_URL;
    }

    /**
     * @return  查询退款订单URL
     */
    public static String getRefundQueryURL() {
        return getRandomHost() + REFUND_QUERY_URL;
    }

    /**
     * @return  查询退款订单状态URL
     */
    public static String getRefundStatusURL() {
        return getRandomHost() + REFUND_STATUS_QUERY_URL;
    }

    public static String getPayPalAccessTokenUrl() {
        if (BCCache.getInstance(null).paypalPayType == BCPay.PAYPAL_PAY_TYPE.LIVE)
            return PAYPAL_LIVE_BASE_URL + PAYPAL_ACCESS_TOKEN_URL;
        else
            return PAYPAL_SANDBOX_BASE_URL + PAYPAL_ACCESS_TOKEN_URL;
    }

    /**
     * @return  HttpClient实例
     */
    public static HttpClient wrapClient() {
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore
                    .getDefaultType());
            trustStore.load(null, null);
            SSLSocketFactory sf = new BCSSLSocketFactory(trustStore);
            sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

            HttpParams params = new BasicHttpParams();

            HttpConnectionParams.setConnectionTimeout(params, BCCache.getInstance(null).networkTimeout);
            HttpConnectionParams.setSoTimeout(params, BCCache.getInstance(null).networkTimeout);

            HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
            HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

            SchemeRegistry registry = new SchemeRegistry();
            registry.register(new Scheme("http", PlainSocketFactory
                    .getSocketFactory(), 80));
            registry.register(new Scheme("https", sf, 443));

            ClientConnectionManager ccm = new ThreadSafeClientConnManager(
                    params, registry);

            return new DefaultHttpClient(ccm, params);
        } catch (Exception e) {
            return new DefaultHttpClient();
        }
    }

    /**
     * http get 请求
     * @param url   请求uri
     * @return      HttpResponse请求结果实例
     */
    public static HttpResponse httpGet(String url) {
        HttpClient client = wrapClient();

        HttpGet httpGet = new HttpGet(url);
        HttpResponse response = null;
        try {
            response = client.execute(httpGet);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return response;
    }

    /**
     * http post 请求
     * @param url       请求url
     * @param entity    post参数
     * @return          HttpResponse请求结果实例
     */
    public static HttpResponse httpPost(String url, StringEntity entity) {
        HttpClient client = wrapClient();

        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(entity);

        try {
            return client.execute(httpPost);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * http post 请求
     * @param url       请求url
     * @param para      post参数
     * @return          HttpResponse请求结果实例
     */
    public static HttpResponse httpPost(String url, Map<String, Object> para) {
        Gson gson = new Gson();
        String param = gson.toJson(para);

        Log.w("BCHttpClientUtil", param);

        StringEntity entity;
        try {
            entity = new StringEntity(param, HTTP.UTF_8);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
        entity.setContentType("application/json");
        return httpPost(url, entity);
    }

    public static HttpResponse getPayPalAccessToken() {
        HttpClient client = wrapClient();

        HttpPost httpPost = new HttpPost(getPayPalAccessTokenUrl());
        httpPost.addHeader("Accept", "application/json");
        httpPost.addHeader("Authorization", BCSecurityUtil.getB64Auth(
                BCCache.getInstance(null).paypalClientID, BCCache.getInstance(null).paypalSecret));

        StringEntity entity;
        try {
            entity = new StringEntity("grant_type=client_credentials", HTTP.UTF_8);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }

        entity.setContentType("application/x-www-form-urlencoded");

        httpPost.setEntity(entity);

        try {
            return client.execute(httpPost);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

}
