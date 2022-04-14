package com.lamark.business.triyacom.core.constants;

public class Constants {

    //Proveedor de campañas adNetNov20

    public final  static String  operationSubs_AdNetNov20 = "S";
    public final  static String  operationDelivery_AdNetNov20 = "D";
    public final  static String paramTypeName = "type";
    public final  static String paramTypeValue = "mt";
    public static final String SHORTCODE_TRIYACOM = "93230";

    //pbp

    public static String USER_TYPE = "MSISDN";
    public static String PARTNER_PRODUCT_CODE_1RA_OPCION_PROD = "LM100MB3D";
    public static String PARTNER_PRODUCT_CODE_3RA_OPCION_PROD = "LM400MB3D";
    public static final String ZONE = "Asia/Jakarta";
    public static final String ZONE_LIMA = "America/Lima";
    public static final String INTEGRATOR_TRIYACOM = "TRY";
    public static final Integer STATUS_PRESENT_ACTIVE = 1;
    public static final Integer INTEGRATION_TYPE_IN_SUBSCRIPTION_TRIYACOM = 1;
    public static final Integer INTEGRATION_TYPE_IN_DELIVERY_TRIYACOM = 2;

    //wap

    public static String SUFFIX_CAMPAIGN_MCS = "A";
    public static String SUFFIX_CAMPAIGN_INDOSAT = "B";
    public static String SUFFIX_CAMPAIGN_ADNETNOV20 = "C";
    public static String VALUE_DB_CAMPAIGN_MCS = "MCS";
    public static String VALUE_DB_CAMPAIGN_INDOSAT = "APIGATE";
    public static String VALUE_DB_CAMPAIGN_ADNETNOV20= "ADNETNOV20";
    public static String WITHOUT_TID = "without_tid";
    public static String WITH_TID = "with_tid";
    public static String SITE_XL = "149";
    public static String ADS = "3ADS1";

    //TriyakomConfig
    public static final String CONTENT_PACKAGE = "contentPackage";
    public static final String PRICE_CODE = "priceCode";
    public static final String AMOUNT = "amount";
    public static final String MESSAGE = "message";
    public static final String SERVICE_SMS = "serviceSMS";
    public static final String SERVICE_FIRST_PUSH = "serviceFirstPush";
    public static final String SERVICE_RENEW = "serviceRenew";
    public static final String SAVE_PUSH = "savePush";
    public static final String APP_PWD_SMS = "appPwdSMS";
    public static final String APP_PWD_FIRST_PUSH = "appPwdFirstPush";
    public static final String APP_PWD_RENEW = "appPwdRenew";
    public static final String APP_ID_SMS = "appIdSMS";
    public static final String APP_ID_FIRST_PUSH = "appIdFirstPush";
    public static final String APP_ID_RENEW = "appIdRenew";
    public static final String DAYS = "days";
    public static final String SITE_ID = "siteId";
    public static final String CONTENT_MESSAGE = "contentMessage";
    public static final String UNREG_MESSAGE = "unregMessage";
    public static final String MESSAGE_RENEW = "messageRenew";
    public static final String ALPHABET = "alphabet";
    public static final String ID_SAVE_PUSH = "idSavePush";
    public static final String LM_CONF_TRIYACOM = "LM_CONF_TRIYACOM";
    public static final String SERVICE_UNREG = "serviceUnreg";
    public static final String APP_ID_UNREG = "appIdUnreg";
    public static final String APP_PWD_UNREG = "appPwdUnreg";
    public static final String SUBSCRIPTION_VALIDITY = "subscriptionValidity";

    public static enum PbpOperators {
        OPERATOR_INDOSAT("IM3");

        private String operatorId;

        private PbpOperators(String i) {
            this.operatorId = i;
        }

        public String getOperatorId() {
            return this.operatorId;
        }

        public void setOperatorId(String operatorId) {
            this.operatorId = operatorId;
        }
    }



}
