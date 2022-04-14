package com.lamark.business.triyacom.core.client;

import com.lamark.architecture.corems.exception.BaseException;
import com.lamark.shared.dto.RegistrationDto;
import com.lamark.shared.dto.RetryDto;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;


import javax.ws.rs.*;


@RegisterRestClient(configKey = "data-triyakom")
@Path("/data/v1/triyacom")
public interface DataTriyakomClient {

    @GET
    @Path("/registration/findByPushId/{pushId}")
    RegistrationDto findByPushId(@PathParam("pushId") String pushId);

    @GET
    @Path("/retry/findRetryById/{id}")
    @Consumes("application/json")
    RetryDto findRetryById(@PathParam("id") Long id);

    @POST
    @Path("/retry/save")
    void saveRetry(RetryDto userRetryDto);

    @POST
    @Path("/registration")
    void saveRegistration(RegistrationDto registration) throws BaseException;

    @POST
    @Path("/registration/returnId")
    String saveRegistrationReturnId(RegistrationDto registration) throws BaseException;

    @POST
    @Path("/retry/update")
    void updateRetry(RetryDto retryDto);

}
