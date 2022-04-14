package com.lamark.business.triyacom.core.util;

import com.lamark.architecture.corems.exception.BaseException;
import com.lamark.architecture.corems.exception.ExceptionHelper;
import com.lamark.shared.dto.LamarkConfigDto;
import com.lamark.shared.dto.TriyakomConfigDto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lamark.business.triyacom.core.constants.Constants.*;

public class Util {
    private static final String WAP_TAG = "W";

    public static String addZero(String msisdn) {
        String zero = msisdn.substring(2, 3);
        if (BigDecimal.ZERO.toString().equals(zero)) {
            return msisdn;
        } else {
            StringBuffer sb = new StringBuffer(msisdn.substring(0, 2)).append(BigDecimal.ZERO);
            sb = sb.append(msisdn.substring(2));
            return sb.toString();
        }
    }

    public static String removeZero(String msisdn){
        String zero = msisdn.substring(2,3);
        if(BigDecimal.ZERO.toString().equals(zero)){
            StringBuffer sb = new StringBuffer(msisdn.substring(0,2));
            sb = sb.append(msisdn.substring(3));
            return sb.toString();
        }else{
            return msisdn;
        }
    }


    public static Integer toInteger(String integer) throws BaseException {
        if (integer != null)
            return Integer.valueOf(integer);
        else
            throw ExceptionHelper.buildGenericException("Valor nulo no se puede convertir a entero");
    }

    public static TriyakomConfigDto getTriyakomConfig(List<LamarkConfigDto> lmConfs, String shortCode, String operator, String keyword) throws BaseException {
        Map<String, String> conf = lmConfs.stream()
                .filter(s -> shortCode.equalsIgnoreCase(s.getValue1()))
                .filter(s -> operator.equalsIgnoreCase(s.getValue2()))
                .filter(s -> keyword.equalsIgnoreCase(s.getValue3()))
                .collect(Collectors.toMap(LamarkConfigDto::getValue4, LamarkConfigDto::getValue5));
        TriyakomConfigDto dto = new TriyakomConfigDto();
        dto.setAlphabet(conf.get(ALPHABET));
        dto.setAmount(conf.get(AMOUNT));
        dto.setAppIdFirstPush(conf.get(APP_ID_FIRST_PUSH));
        dto.setAppIdRenew(conf.get(APP_ID_RENEW));
        dto.setAppIdSMS(conf.get(APP_ID_SMS));
        dto.setAppPwdFirstPush(conf.get(APP_PWD_FIRST_PUSH));
        dto.setAppPwdRenew(conf.get(APP_PWD_RENEW));
        dto.setAppPwdSMS(conf.get(APP_PWD_SMS));
        dto.setContentMessage(conf.get(CONTENT_MESSAGE));
        dto.setContentPackage(conf.get(CONTENT_PACKAGE));
        dto.setDays(conf.get(DAYS));
        dto.setIdSavePush(conf.get(ID_SAVE_PUSH));
        dto.setMessage(conf.get(MESSAGE));
        dto.setMessageRenew(conf.get(MESSAGE_RENEW));
        dto.setPriceCode(conf.get(PRICE_CODE));
        dto.setServiceFirstPush(conf.get(SERVICE_FIRST_PUSH));
        dto.setServiceRenew(conf.get(SERVICE_RENEW));
        dto.setServiceSMS(conf.get(SERVICE_SMS));
        dto.setSiteId(conf.get(SITE_ID));
        dto.setUnregMessage(conf.get(UNREG_MESSAGE));
        dto.setSavePush(conf.get(SAVE_PUSH));
        dto.setServiceUnreg(conf.get(SERVICE_UNREG));
        dto.setAppIdUnreg(conf.get(APP_ID_UNREG));
        dto.setAppPwdUnreg(conf.get(APP_PWD_UNREG));
        dto.setSubscriptionValidity(conf.get(SUBSCRIPTION_VALIDITY));
        return dto;
    }

}
