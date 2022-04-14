package com.lamark.business.triyacom.core.service.impl;

import com.lamark.architecture.corems.exception.BaseException;
import com.lamark.architecture.corems.exception.ExceptionHelper;
import com.lamark.architecture.corems.util.DateUtil;
import com.lamark.business.triyacom.core.client.*;
import com.lamark.business.triyacom.core.constants.Constants;
import com.lamark.business.triyacom.core.constants.MTConstant;
import com.lamark.business.triyacom.core.dto.PushResponse;
import com.lamark.business.triyacom.core.dto.RequestOperator;
import com.lamark.business.triyacom.core.service.TriyakomService;
import com.lamark.business.triyacom.core.util.UUIDUtil;
import com.lamark.business.triyacom.core.util.Util;
import com.lamark.shared.dto.*;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.lamark.architecture.corems.util.DateUtil.*;
import static com.lamark.business.triyacom.core.constants.Constants.ADS;
import static com.lamark.business.triyacom.core.util.LiteralConstant.SPACE;
import static com.lamark.business.triyacom.core.util.Util.addZero;
import static com.lamark.business.triyacom.core.util.Util.toInteger;
import static com.lamark.shared.dto.EventTypeDto.SUSPEND;
import static com.lamark.shared.dto.EventTypeDto.SUSPEND_BY_RETRY;
import static com.lamark.shared.dto.Keyword.*;
import static com.lamark.shared.dto.Operator.HTI;
import static com.lamark.shared.dto.SourceOfCreationDto.SMS;
import static com.lamark.shared.dto.SourceOfCreationDto.WAP;
import static com.lamark.shared.dto.SubscriptionPrefix.REG;

@Singleton
public class TriyakomServiceImpl implements TriyakomService {

    private static final String DELIVERY_ERROR = "[ERROR-DELIVERY] {0} {1}";
    private static final String TID_NR_ERROR = "[ERROR-EN-DELIVERY-NO EXISTE REGISTRO EN TRIYACOM SUBSCRIPTION] {} {}";
    private static final String UNKNOWN_CODE_ERROR = "[ERROR-UNKNOWN-CODE] {} {} {}";
    private static final String BILLING_INFO = "[BILLING-SUCCESS] {}";
    private static final String TAG_INFO = "[TAG] {} {}";
    private static final Integer UNDELIVERABLE = 101;
    private static final Integer BILLING_SUCCESS = 102;
    private static final Integer INSUFFICIENT_BALANCE = 103;
    private static final String WAP_TAG = "W";

    @Inject
    @RestClient
    WapClient wapClient;

    @Inject
    @RestClient
    DataCoreClient dataCoreClient;

    @Inject
    @RestClient
    DataTriyakomClient dataTriyakomClient;

    @Inject
    @RestClient
    BusinessCommandClient businessCommandClient;

    @Inject
    @RestClient
    PbpClient pbpClient;

    @Inject
    @RestClient
    SendMTClient sendMTClient;

    @Inject
    @RestClient
    SupportIntegrationClient supportIntegrationClient;

    private static final String ACTIVATION = "activation";
    private static final String SI = "S";
    private static final String URL = "url";
    public static final String ZONE = "Asia/Jakarta";
    public static final SimpleDateFormat FORMAT_DATETIME_SEND_PIXEL = new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss");
    private Logger logger = LoggerFactory.getLogger(this.getClass());


    @Override
    public void subscription(RegistrationData registrationData) throws BaseException, InvocationTargetException, IllegalAccessException {

        String[] commandKeyword = registrationData.getCommand().toUpperCase().split(" ");

        String command = commandKeyword[0];
        String keyword = getKeyword(commandKeyword, registrationData.getOperator());
        String channel = getChannel(commandKeyword, registrationData.getOperator());
        String suffixAd = commandKeyword.length == 4 ? commandKeyword[3].substring(commandKeyword[3].length() - 1) : "";

        List<LamarkConfigDto> lmConfsTriyakom = dataCoreClient.getConfig("LM_CONF_TRIYACOM");

        TriyakomConfigDto triyakomConfig = Util.getTriyakomConfig(lmConfsTriyakom, registrationData.getShortCode() , registrationData.getOperator(), keyword);

        //msisdnBD : Voucher code en caso de XL y para otros operadores con el 0 en la tercera posicion
        String msisdnBD = triyakomConfig.getSiteId().equals(Constants.SITE_XL) ? registrationData.getSource() : addZero(registrationData.getSource());

        String adProvider = getOperatorCampaignsWapConfig(channel, suffixAd);
        switch (command) {
            case "REG":
                subscribe(registrationData, triyakomConfig, keyword, channel, msisdnBD, adProvider);
                break;
            case "UNREG":
            case "OFF":
                cancel(registrationData, triyakomConfig, keyword, msisdnBD);
                break;
        }

    }

    private void subscribe(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, String keyword, String channel, String msisdnBD, String adProvider) throws BaseException, InvocationTargetException, IllegalAccessException {

        UserDto userDto = null;
        boolean userRegistered = validateUserRegister(Integer.valueOf(triyakomConfig.getSiteId()), msisdnBD);

        if (!userRegistered) {
            ResponseSubscribeDto responseSubscribeDto = callCommandSuscribe(registrationData, triyakomConfig, keyword, channel, msisdnBD);

            if (triyakomConfig.getSiteId().equals(Constants.SITE_XL))
                msisdnBD = responseSubscribeDto.getMsisdn();

            //UPDATE USER TABLE WITH DATA CAMPAINGS
            updateDataCampaigns(userDto, channel, adProvider, responseSubscribeDto);

            //CREATE RETRY USER
            createRetryUser(responseSubscribeDto.getUserId(), keyword, responseSubscribeDto.isNewUser());

            //SEND MSG THANKS
            PushResponse mtThanks = sendMTThanks(registrationData, triyakomConfig, msisdnBD, keyword);

            //SAVE MT THANKS
            String triyakomSubscriptionId = saveTriyakomSubscriptionMsgThanks(registrationData, triyakomConfig, mtThanks, msisdnBD);

            if(!HE1000.equals(keyword)) {
                //SEND FIRST PUSH
                PushResponse firstPush = sendFirstPush(registrationData, triyakomConfig);
                //SAVE FIRST PUSH
                triyakomSubscriptionId = saveTriyakomSubscriptionFirstPush(registrationData, triyakomConfig, firstPush, triyakomSubscriptionId, msisdnBD);
            }
            //UPDATE VOUCHER CODE IN TRIYACOM_SUBSCRIPTION FOR XL
            updateVoucherCode(triyakomConfig.getSiteId(), msisdnBD, triyakomSubscriptionId);

            //SEND PIXEL
            sendPixelProcess(triyakomConfig.getSiteId(), msisdnBD, keyword, adProvider, registrationData.getOperator(), channel, registrationData, "1");

            //SEND PBP
//            sendPbp(registrationData.getOperator(), keyword, msisdnBD, 0);
        }
    }

    @Override
    public Uni<Void> delivery(DeliveryData data) {
        try {
            RegistrationDto registration = dataTriyakomClient.findByPushId(data.getTid());
            logger.info("[DELIVERY][LOGIC]: {}", registration);
            if (registration != null) {
                if (registration.getDeliveryDate() == null) {
                    RegistrationData registrationData = registration.getPayload();
                    String[] command = registrationData.getCommand().split(SPACE);
                    String keyword = getKeyword(command, data.getOperator());
                    String channel = getChannel(command, data.getOperator());

                    List<LamarkConfigDto> lmConfsTriyakom = dataCoreClient.getConfig("LM_CONF_TRIYACOM");

                    TriyakomConfigDto triyakomConfig = Util.getTriyakomConfig(lmConfsTriyakom, registrationData.getShortCode() , registrationData.getOperator(), keyword);
                    String msisdn = registration.getMsisdn();
                    registration.setDeliveryPayload(data);
                    registration.setDeliveryDate(DateUtil.getLocalDateTime(ZONE));
                    registration.setDeliveryStatus(data.getStatus());
                    Integer site = Integer.valueOf(triyakomConfig.getSiteId());
                    if (BILLING_SUCCESS.equals(data.getStatus())) {
                        if (REG.equals(command[0])) {
                            String suffixCampaign, adProvider = null;
                            if (channel!=null && channel.equals(WAP.toString())) {
                                suffixCampaign = command[3].substring(command[3].length() - 1, command[3].length());
                                adProvider = getOperatorCampaignsWapConfig(channel, suffixCampaign);
                                if (registration.getType().equals(SendMTTag.REG)) {
                                    sendPixelProcess(triyakomConfig.getSiteId(), msisdn, keyword, adProvider, registrationData.getOperator(),
                                            channel, registrationData, "2");
                                }
                            }
                            Integer priceCode = Integer.valueOf(triyakomConfig.getPriceCode());
                            Integer contentPackage = Integer.valueOf(triyakomConfig.getContentPackage());
                            String amount = triyakomConfig.getAmount();

                            SubscriptionDataDto subscription = new SubscriptionDataDto();
                            subscription.setSiteId(site);
                            subscription.setMsisdn(msisdn);
                            subscription.setPriceCodeId(priceCode);
                            subscription.setContentPackageId(contentPackage);
                            subscription.setAmount(amount);
                            subscription.setType(EventTypeDto.RENEW.name());
                            subscription.setMakeBilling(true);
                            subscription.setBillingDate(getDateZone(ZONE));
                            subscription.setPurchaseDate(getDateZone(ZONE));
                            subscription.setAccountCreditsDate(getDateZone(ZONE));
                            subscription.setLastModifiedDate(getDateZone(ZONE));
                            subscription.setExpireDate(registration.getRenewDate());
                            subscription.setNextModifiedDate(registration.getRenewDate());
                            SubscriptionResourceDto resource = businessCommandClient.renew(subscription);
                            registration.setChargeRecord(resource.getChargeRecord());
                            disableRetryByUser(resource.getUserId()); // comun con la cancelacion
                            logger.debug(BILLING_INFO, msisdn);
                            if (registration.getType().ordinal() == 0 && registration.getAttempt() == null){
//                                sendPbp(data.getOperator(), keyword_for_pbp, msisdn, PbpOptionsIntegration.FIRST_BILLING.getOptionValue());
                                //Se agrego un delay de 20 seg antes de enviar sendFirstPush
                                if (HE1000.equals(keyword)) {
                                    new Thread(new Runnable() {
                                        public void run() {
                                            try {
                                                TimeUnit.SECONDS.sleep(20);
                                                sendFirstPush(registrationData, triyakomConfig);
                                            } catch (BaseException | InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }).start();
                                }
                            }
//                            else {
//                                logger.debug("numbersOfBillings before query  " + "msisdn " + msisdn + "site " + site);
//                                Integer numbersOfBillings = dataCoreClient.getNumbersOfBilling(msisdn, site);
//                                logger.debug("numbersOfBillings after query  " + numbersOfBillings);
//                                if (numbersOfBillings.equals(2))
//                                    sendPbp(data.getOperator(), keyword_for_pbp, msisdn, PbpOptionsIntegration.SECOND_BILLING.getOptionValue());
//                            }
                        }
                    } else if (INSUFFICIENT_BALANCE.equals(data.getStatus()) || UNDELIVERABLE.equals(data.getStatus())) {
                        if (!MAGICEN1.equals(keyword)) {
                            Date renewDate = registration.getRenewDate();
                            EventTypeDto event = SUSPEND;
                            if (registration.getAttempt() == null || AttemptType.RENEW.equals(registration.getAttempt())) {
                                BigDecimal delta = new BigDecimal(Integer.parseInt(triyakomConfig.getDays())).subtract(BigDecimal.ONE);
                                renewDate = PLUS_DAYS(registration.getRenewDate(), delta.negate());
                            } else if (AttemptType.RETRY.equals(registration.getAttempt())) {
                                renewDate = PLUS_DAYS(registration.getRenewDate(), BigDecimal.ONE.negate());
                                event = SUSPEND_BY_RETRY;
                            }
                            String nextModifiedDate = DateUtil.getStringDate(renewDate, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
                            String dateNowStr = DateUtil.getStringDate(
                                    DateUtil.DATE_ZONE(ZONE),
                                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

                            SuspendDto suspendDto = new SuspendDto();
                            suspendDto.setMsisdn(msisdn);
                            suspendDto.setSite(site);
                            suspendDto.setEventType(event.name());
                            suspendDto.setSuspendDate(dateNowStr);
                            suspendDto.setStrNextModifiedDate(nextModifiedDate);

                            suspendDto.setSuspendDate(dateNowStr);
                            suspendDto.setStrNextModifiedDate(nextModifiedDate);

                            businessCommandClient.suspend(suspendDto);
                        }

                        if (HE1000.equalsIgnoreCase(keyword) && registration.getType().ordinal() == 0) {
                            callCommandCancellation(Integer.valueOf(triyakomConfig.getSiteId()), msisdn);
                        }
                    } else {
                        logger.error(UNKNOWN_CODE_ERROR, data.getStatus(), registrationData.getSource(), data.getTid());
                    }
                    dataTriyakomClient.saveRegistration(registration);
                }
            } else {
                logger.error(TID_NR_ERROR, data.getTid(), data);
            }
        } catch (Exception e) {
            logger.error(MessageFormat.format(DELIVERY_ERROR, data.toString(), e.getMessage()), e);
        }
        return Uni.createFrom().voidItem();
    }

    @Override
    public void processRenewDaily(String dateIniStr, String dateEndStr) {

    }


    public boolean validateUserRegister(Integer siteId, String msisdn) {
        logger.debug("Before get VOucher COde");
        msisdn = siteId.toString().equals(Constants.SITE_XL) ? getVoucherCode(msisdn) : msisdn;
        logger.debug("msisdn " + msisdn);
        if (msisdn == null)
            return false;
        else {
            UserDto userDto = null;
            try {
                userDto = dataCoreClient.getUser(msisdn, siteId);
            } catch (Exception e) {
                logger.debug("No existe usuario");
            }
            if (userDto == null || userDto.getSubscription().getState() < 0) {
                return false;
            } else {
                return true;
            }
        }
    }

    public String getAdProvider(String channel, String suffixAd) {
        String adProvider = null;
        if (channel.equalsIgnoreCase(WAP.name())) {
            if (suffixAd.equalsIgnoreCase(Constants.SUFFIX_CAMPAIGN_MCS))
                adProvider = Constants.VALUE_DB_CAMPAIGN_MCS;
            if (suffixAd.equalsIgnoreCase(Constants.SUFFIX_CAMPAIGN_INDOSAT))
                adProvider = Constants.VALUE_DB_CAMPAIGN_INDOSAT;
            if (suffixAd.equalsIgnoreCase(Constants.SUFFIX_CAMPAIGN_ADNETNOV20))
                adProvider = Constants.VALUE_DB_CAMPAIGN_ADNETNOV20;
        }
        return adProvider;
    }

    private void updateVoucherCode(String siteId, String voucherCode, String idTriyaComSubs) throws BaseException {
        if (siteId.equals(Constants.SITE_XL)) {
            //actualiza voucher code en tabla triyacom_subscription para site 149
            RegistrationDto registrationDto = new RegistrationDto();
            registrationDto.setId(idTriyaComSubs);
            registrationDto.setMsisdn(voucherCode);
            logger.debug("registrationDto update voucher code " + registrationDto);
            dataTriyakomClient.saveRegistration(registrationDto);
        }
    }

    private void sendPixelProcess(String siteId, String msisdnBD, String keyword,
                                  String adPRovider, String operator, String channel,
                                  RegistrationData registrationData, String operationType
    ) throws BaseException {

        String transactionId = null;

        ResponseValidatePixelDto responseValidatePixelDto = supportIntegrationClient
                .validateSendPixel(
                        Integer.valueOf(siteId),
                        msisdnBD,
                        adPRovider,
                        keyword,
                        operator,
                        channel,
                        operationType
                );  //TODO : LM_CONF_CAMPAINGS_PIXEL debe estar correctamente llenada

        logger.info(" Will send Pixel ? : " + responseValidatePixelDto);

        /*INICIO SEND PIXEL*/

        boolean sendPixel = responseValidatePixelDto.isSendPixel();

        if (sendPixel) {
            PixelDto pixelDto = new PixelDto();
            pixelDto.setSiteId(Integer.valueOf(siteId));
            pixelDto.setMsisdnBd(msisdnBD);
            pixelDto.setAdProvider(adPRovider);
            pixelDto.setKeyword(keyword);
            pixelDto.setOperator(operator);
            pixelDto.setOperationType(operationType);
            pixelDto.setShortCode(registrationData.getShortCode());
            if (siteId.equals(Constants.SITE_XL))
                pixelDto.setClientMsisdn(registrationData.getSource());
            else
                pixelDto.setClientMsisdn(Util.removeZero(msisdnBD));//TODO :  enviar LM_CONF_SITE (actualizar), LM_CONF_CAMPAIGNS A produccion
            pixelDto.setUrl(responseValidatePixelDto.getUrl());

            //values
            String[] command = registrationData.getCommand().toUpperCase().split(" ");
            if (channel.toUpperCase().equals("WAP")) {
                transactionId = command[3].substring(0, command[3].length() - 1);
            }
            pixelDto.setTransactionId(transactionId);
            pixelDto.setDateSend(FORMAT_DATETIME_SEND_PIXEL.format(DateUtil.DATE_ZONE(ZONE)));
            sendPixel(pixelDto); //TODO : Agregar en keyword pixel url log campo operator
        }

    }

    private void updateDataCampaigns(UserDto userDto, String channel, String nameOperatorCompaign, ResponseSubscribeDto responseSubscribeDto) throws InvocationTargetException, IllegalAccessException, BaseException {
        userDto = dataCoreClient.getUser(responseSubscribeDto.getUserId());
        logger.debug("userDto update campaings = " + userDto);
        if (channel.equals(WAP.toString())) { //TODO : COMPLETAR LO DEL SEND PIXEL PARA DELIVERY
            userDto.setChannel(WAP.toString().concat(SPACE).concat(nameOperatorCompaign));
            userDto.setKeywordDescription(nameOperatorCompaign);
        } else {
            userDto.setChannel(channel.equals(ADS) ? channel : SMS.toString());
            userDto.setKeywordDescription(null);
        }
        logger.debug("Usuario a actualizar " + userDto);
        dataCoreClient.updateUser(userDto);
    }


    private void createRetryUser(Long user, String keyword, boolean isNewUser) {
        logger.debug("Init createRetryUser");
        logger.debug(" keyword " + keyword);
        if (!keyword.equals(HE1)) {
            if (!keyword.equals(MAGICEN1)) {
                if (!keyword.equals(ME1)) {
                    if (!keyword.equals(ME) &&
                            !keyword.startsWith(ME_PRM)
                    ) {
                        logger.debug("INicia grabado ");
                        RetryDto retryDto = new RetryDto();
                        if (isNewUser) {
                            retryDto.setId(user);
                            retryDto.setHour(HOUR_ZONE(ZONE));
                            retryDto.setDateRetry(PLUS_DAY(DATE_ZONE(ZONE)));
                            retryDto.setEnable(true);

                            long hourStart = System.currentTimeMillis();
                            logger.debug("Inicio interaccion con bd 1");
                            dataTriyakomClient.saveRetry(retryDto);
                            logger.debug("Fin interaccion con bd 1 - Time Process : " + (System.currentTimeMillis() - hourStart));

                        } else {
                            enableRetryByUser(user, HOUR_ZONE(ZONE));
                        }
                    }
                }
            }
        }
    }


    private void enableRetryByUser(Long user, int hour) {
        RetryDto retryDto = dataTriyakomClient.findRetryById(user);
        if (retryDto.getId() != null) {
            retryDto.setEnable(true);
            retryDto.setHour(hour);
            retryDto.setDateRetry(PLUS_DAY(DATE_ZONE(ZONE)));
            dataTriyakomClient.updateRetry(retryDto);
        } else {
            retryDto.setId(user);
            retryDto.setHour(HOUR_ZONE(ZONE));
            retryDto.setDateRetry(PLUS_DAY(DATE_ZONE(ZONE)));
            retryDto.setEnable(true);
            dataTriyakomClient.saveRetry(retryDto);
        }

    }

    private void cancel(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, String keyword, String msisdnBd) throws BaseException {
        msisdnBd = (triyakomConfig.getSiteId().equals(Constants.SITE_XL)) ? getVoucherCode(registrationData.getSource()) : msisdnBd;
        saveTriyakomSubscription(registrationData, triyakomConfig, null, SendMTTag.UNREG, null, msisdnBd);

        switch (keyword) {
            case HE1000:
                sendMT(SendMTTag.UNREG,
                        registrationData.getSource(),
                        registrationData.getOperator(),
                        triyakomConfig.getUnregMessage(),
                        triyakomConfig.getAppIdUnreg(),
                        triyakomConfig.getAppPwdUnreg(),
                        triyakomConfig.getServiceUnreg(),
                        triyakomConfig.getAlphabet(),
                        registrationData.getTid());
                break;
            default:
                sendMT(SendMTTag.UNREG,
                        registrationData.getSource(),
                        registrationData.getOperator(),
                        triyakomConfig.getUnregMessage(),
                        triyakomConfig.getAppIdSMS(),
                        triyakomConfig.getAppPwdSMS(),
                        triyakomConfig.getServiceSMS(),
                        triyakomConfig.getAlphabet(),
                        registrationData.getTid());
                break;
        }

        callCommandCancellation(Integer.valueOf(triyakomConfig.getSiteId()), msisdnBd);

        UserDto userDto = null;
        try {
            userDto = dataCoreClient.getUser(msisdnBd, Integer.valueOf(triyakomConfig.getSiteId()));
        } catch (Exception e) {
        }

        disableRetryByUser(userDto.getUserId());
    }

    private String getVoucherCode(String cypherNumber) {
        return dataCoreClient.getMsisdn(Integer.valueOf(Constants.SITE_XL), "X-Source-Addr", cypherNumber).getMsisdn();
    }

    private PushResponse sendMTThanks(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, String msisdnBd, String keyword) throws BaseException {
        String welcomeMessage = triyakomConfig.getSiteId().equals(Constants.SITE_XL) ? getFormatMessage(triyakomConfig.getMessage(), msisdnBd.substring(2)) : triyakomConfig.getMessage();
        if ("HTI".equalsIgnoreCase(registrationData.getOperator())) {
            if (keyword.equals(HE1000)){
                welcomeMessage = getFormatMessage(welcomeMessage, Util.toInteger(triyakomConfig.getSubscriptionValidity()));
            }else {
                welcomeMessage = getFormatMessage(welcomeMessage, Util.toInteger(triyakomConfig.getDays()));
            }
        }
        logger.debug("welcomeMessage " + welcomeMessage);
        /*sendMT(SendMTTag tag, String msisdn, String operator, String message, String appId,
                String appPwd, String service, String alphabet, String tid)*/
        return sendMT(SendMTTag.THANKS, registrationData.getSource(), registrationData.getOperator(), welcomeMessage, triyakomConfig.getAppIdSMS(), triyakomConfig.getAppPwdSMS(),
                triyakomConfig.getServiceSMS(), triyakomConfig.getAlphabet(), registrationData.getTid());
    }


    private PushResponse sendFirstPush(RegistrationData registrationData, TriyakomConfigDto triyakomConfig) throws BaseException {
        String contentMessage = getFormatMessage(triyakomConfig.getContentMessage(), Util.toInteger(triyakomConfig.getDays()));

        /*sendMT(SendMTTag tag, String msisdn, String operator, String message, String appId,
                String appPwd, String service, String alphabet, String tid)*/

        return sendMT(SendMTTag.REG, registrationData.getSource(), registrationData.getOperator(), contentMessage,
                triyakomConfig.getAppIdFirstPush(), triyakomConfig.getAppPwdFirstPush(), triyakomConfig.getServiceFirstPush(),
                triyakomConfig.getAlphabet(), null);

    }

    public static String getFormatMessage(String message, int days) {
        SimpleDateFormat FORMAT = new SimpleDateFormat("dd:MM:yyyy");
        Date date = DateUtil.DATE_ZONE(ZONE);
        return MessageFormat.format(message, FORMAT.format(DateUtil.PLUS_DAYS(date, days)));
    }


    private String getFormatMessage(String message, String param) {
        return MessageFormat.format(message, param);
    }


    public PushResponse sendMT(SendMTTag tag, String msisdn, String operator, String message, String appId,
                               String appPwd, String service, String alphabet, String tid) throws BaseException {


        Map<String, String> mt = new HashMap<>();
        mt.put(MTConstant.MSISDN, msisdn);
        mt.put(MTConstant.OPERATOR, operator);
        mt.put(MTConstant.MESSAGE, message);
        mt.put(MTConstant.APP_ID, appId);
        mt.put(MTConstant.SERVICE, service);
        mt.put(MTConstant.APP_PWD, appPwd);
        mt.put(MTConstant.ALPHABET, alphabet);
        if (tid != null) {
            mt.put(MTConstant.TRX, tid);
        }
        logger.info("----------------------REQUEST----------------------: {}", mt);
        String response;
        if (tid != null) {
            response = sendMTClient.sendMT(msisdn, operator, message, appId, service, appPwd, alphabet, tid);
        } else {
            response = sendMTClient.sendMT(msisdn, operator, message, appId, service, appPwd, alphabet);
        }

        logger.info(TAG_INFO, tag.name(), response);
        return getResponse(response);
    }

    private PushResponse getResponse(String xml) throws BaseException {
        try {
            JAXBContext context = JAXBContext.newInstance(PushResponse.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            return (PushResponse) unmarshaller.unmarshal(new StringReader(xml));
        } catch (JAXBException e) {
            throw ExceptionHelper.buildGenericException("");
        }
    }

    private String saveTriyakomSubscriptionMsgThanks(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, PushResponse pushResponse, String msisdnBd) throws BaseException {

        String id = null;
        if (triyakomConfig.getIdSavePush().contains(triyakomConfig.getAppIdSMS())) { //[PRM1][PRM2] SAVE THANKS
            logger.info("-------------------MsgThanks----------------------");
            logger.info("IdSavePush={}, AppIdFirstPush={}, " + triyakomConfig.getIdSavePush(), triyakomConfig.getAppIdFirstPush());
            logger.info("pushResponse id:{} status:{}", pushResponse.getId(), pushResponse.getStatus());
            RegistrationDto triyaconSubscription = new RegistrationDto();
            triyaconSubscription.setMsisdn(msisdnBd);
            triyaconSubscription.setPayload(registrationData);
            triyaconSubscription.setType(SendMTTag.REG);
            triyaconSubscription.setOperator(registrationData.getOperator());
            triyaconSubscription.setDateCreated(DateUtil.DATE_ZONE(ZONE));
            triyaconSubscription.setRenewDate(PLUS_DAYS(DateUtil.DATE_ZONE(ZONE), Util.toInteger(triyakomConfig.getDays())));
            triyaconSubscription.setPushId(pushResponse.getId()!=null ? pushResponse.getId() : null);
            triyaconSubscription.setPushStatus(pushResponse.getStatus()!=null ? pushResponse.getStatus() : null);
            id = dataTriyakomClient.saveRegistrationReturnId(triyaconSubscription);
        }
        return id;
    }

    private String saveTriyakomSubscriptionFirstPush(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, PushResponse pushResponse, String triyakomSubscriptionId, String msisdnBd) throws BaseException {

        String id = null;

        if (triyakomConfig.getIdSavePush().contains(triyakomConfig.getAppIdFirstPush())) { //[PRM1][PRM2] SAVE THANKS
            logger.info("----------------------------FirstPush---------------------------");
            logger.info("IdSavePush={}, AppIdFirstPush={}, " + triyakomConfig.getIdSavePush(), triyakomConfig.getAppIdFirstPush());
            logger.info("pushResponse id:{} status:{}", pushResponse.getId(), pushResponse.getStatus());
            RegistrationDto triyaconSubscription = new RegistrationDto();
            triyaconSubscription.setId(triyakomSubscriptionId);//if triyakomSubscriptionId==null save else update
            triyaconSubscription.setMsisdn(msisdnBd);
            triyaconSubscription.setPayload(registrationData);
            triyaconSubscription.setType(SendMTTag.REG);
            triyaconSubscription.setOperator(registrationData.getOperator());
            triyaconSubscription.setDateCreated(DateUtil.DATE_ZONE(ZONE));
            triyaconSubscription.setRenewDate(PLUS_DAYS(DateUtil.DATE_ZONE(ZONE), Util.toInteger(triyakomConfig.getDays())));
            if (pushResponse != null) {
                triyaconSubscription.setPushId(pushResponse.getId());
                triyaconSubscription.setPushStatus(pushResponse.getStatus());
            }
            id = dataTriyakomClient.saveRegistrationReturnId(triyaconSubscription);
        }

        return id;
    }

    private void saveTriyakomSubscription(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, PushResponse pushResponse, SendMTTag sendMTTag, String triyakomSubscriptionId, String msisdnBd) throws BaseException { //TODO : EXPLICAR A LEONARDO QUE SUBIO ERRORES CON SU METODO DE SAVETRIYACOMSUBSCRIPTION()

        RegistrationDto triyaconSubscription = new RegistrationDto();
        triyaconSubscription.setId(triyakomSubscriptionId);
        triyaconSubscription.setMsisdn(msisdnBd);
        triyaconSubscription.setPayload(registrationData);
        triyaconSubscription.setType(sendMTTag);
        triyaconSubscription.setOperator(registrationData.getOperator());
        triyaconSubscription.setDateCreated(DateUtil.DATE_ZONE(ZONE));
        triyaconSubscription.setRenewDate(PLUS_DAYS(DateUtil.DATE_ZONE(ZONE), Util.toInteger(triyakomConfig.getDays())));
        if (pushResponse != null) {
            triyaconSubscription.setPushId(pushResponse.getId());
            triyaconSubscription.setPushStatus(pushResponse.getStatus());
        }
        dataTriyakomClient.saveRegistration(triyaconSubscription);
    }


    private ResponseSubscribeDto callCommandSuscribe(RegistrationData registrationData, TriyakomConfigDto triyakomConfig, String keyword, String channel, String msisdnBd) throws BaseException {
        Date now = DateUtil.DATE_ZONE(ZONE);

        SubscriptionDto dto = new SubscriptionDto();
        dto.setSite(toInteger(triyakomConfig.getSiteId()));
        dto.setMsisdn(msisdnBd);
        dto.setKeyword(keyword);
        dto.setContentPackage(toInteger(triyakomConfig.getContentPackage()));
        dto.setPriceCode(triyakomConfig.getPriceCode());
        dto.setAmount(triyakomConfig.getAmount());
        dto.setChannel(channel);
        dto.setSubscriptionDate(now);
        dto.setMakeBilling(false);

        dto.setSubscriptionDuration(Integer.valueOf(triyakomConfig.getDays()));
        if (triyakomConfig.getSiteId().equals(Constants.SITE_XL))
            dto.setGenerateVoucherCode(true);

        //TODO:  revisar el ad provider en la suscripcion
        ResponseSubscribeDto responseSubscribeDto = businessCommandClient.subscribe(dto);

        return responseSubscribeDto;
    }

    private void callCommandCancellation(Integer siteId, String msisdnBd) throws BaseException {
        CancellationDto cancellationDto = new CancellationDto();
        cancellationDto.setMsisdn(msisdnBd);
        cancellationDto.setSiteId(siteId);
        cancellationDto.setEventType(EventTypeDto.CANCEL);
        cancellationDto.setCancellationDate(DateUtil.getLocalDateTime(ZONE));
        businessCommandClient.cancel(cancellationDto);//TODO : NO esta cancelando con fecha hora de indonesia / corregir
    }


    private void sendPixel(PixelDto pixelDto) {
        //TODO :  enviar LM_CONF_SITE (actualizar), LM_CONF_CAMPAIGNS A produccion
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Runnable runnable = () -> {
            try {
                supportIntegrationClient.sendPixel(pixelDto);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                executor.shutdown();
            }
        };
        executor.submit(runnable);
    }

    private void disableRetryByUser(Long user) {
        RetryDto retryDto = dataTriyakomClient.findRetryById(user);
        if (retryDto.getId() != null) {
            retryDto.setEnable(false);
            dataTriyakomClient.updateRetry(retryDto);
        }
    }


    private void sendPbp(String operator, String keyword, String msisdn, Integer numberScenario) {

        RequestOperator requestPbp = new RequestOperator();

        requestPbp.setOperator(operator);
        requestPbp.setIntegrator(Constants.INTEGRATOR_TRIYACOM);
        requestPbp.setKeyword(keyword);
        requestPbp.setOptionRecaudation(numberScenario);
        requestPbp.setRequestID(UUIDUtil.getPlainUUID());
        requestPbp.setUserType(Constants.USER_TYPE);
        requestPbp.setUserID(msisdn);
        if (numberScenario.equals(PbpOptionsIntegration.SUSCRIPCION.getOptionValue()))
            requestPbp.setPartnerProductCode(Constants.PARTNER_PRODUCT_CODE_1RA_OPCION_PROD);
        if (numberScenario.equals(PbpOptionsIntegration.SECOND_BILLING.getOptionValue()))
            requestPbp.setPartnerProductCode(Constants.PARTNER_PRODUCT_CODE_3RA_OPCION_PROD);

        logger.debug("Sending request : {" + requestPbp + "} a handler API GATE");

        logger.debug("Enviando a ejecutor pbp");
        long hourStart = System.currentTimeMillis();
        logger.debug("Adquisicion thread pool ");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        logger.debug("Adquisicion thread pool  " + (System.currentTimeMillis() - hourStart));
        Runnable runnable = () -> {
            try {
                pbpClient.send(requestPbp);
            } catch (Exception e) {
                logger.error(e.getMessage());
            } finally {
                executor.shutdown();
            }
        };
        executor.submit(runnable);
        logger.debug("Fin Enviando a ejecutor pbp");
    }

    private String getOperatorCampaignsWapConfig(String channel, String suffixCampaignsWap) throws BaseException {
        String operatorCampaign = null;
        if (channel.equals(WAP.name())) {

            List<LamarkConfigDto> lmConfsOperatorCampaings = dataCoreClient.getConfig("LM_CONF_OPERATOR_CAMPAINGS_WAP");

            LamarkConfigDto config = lmConfsOperatorCampaings.stream()
                    .filter((x) -> x.getValue1().equals(suffixCampaignsWap))
                    .findFirst()
                    .orElseGet(LamarkConfigDto::new);

            operatorCampaign = config.getValue2();
        }

        return operatorCampaign;

    }

    private String getKeyword(String[] command, String operator) {
        StringBuilder sb = new StringBuilder();
        if (command.length < 2) {
            return null;
        } else {
            if (operator.equals(HTI)) {
                if (command.length == 2) { //REG ME
                    sb.append(command[1]);
                } else {
                    if (command[2].startsWith("PRM")) { //REG ME1 PRM
                        sb.append(command[1]).append(SPACE).append(command[2]);
                    } else {
                        sb.append(command[1]);//REG ME W XXXXX
                    }
                }
            } else {
                sb.append(command[1]);
            }
            return sb.toString();
        }
    }


    private String getChannel(String[] command, String operator) {


        StringBuilder sb = new StringBuilder();
        if (command.length < 2) {
            return null;
        } else {
            if (operator.equals(HTI)) {
                if (command.length == 2) { //REG ME
                    return SMS.name();
                } else {
                    if (command[2].startsWith("PRM")) { //REG ME1 PRM
                        return SMS.name();
                    } else {    //REG ME W XXXXX
                        if (ADS.equals(command[2])){
                            return  ADS;
                        }
                        return WAP.name();
                    }
                }
            } else {
                if (command.length > 2) {
                    if (WAP_TAG.equals(command[2])) {
                        return WAP.name();
                    }
                    return null;
                } else {
                    return SMS.name();
                }
            }
        }

    }
//comment

}
