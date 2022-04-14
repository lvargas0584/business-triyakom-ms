package com.lamark.business.triyacom.core.client;

import com.lamark.architecture.corems.exception.BaseException;
import com.lamark.shared.dto.*;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;


@RegisterRestClient(configKey = "business-command")
@Path("/business/v1/command")
public interface BusinessCommandClient {

    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/subscribe")
    ResponseSubscribeDto subscribe(SubscriptionDto subscriptionDto) throws BaseException;

    @PUT
    @Path("/renew")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    SubscriptionResourceDto renew(SubscriptionDataDto data);

    @PUT
    @Path("/cancel")
    Response cancel(CancellationDto cancellationDto) throws BaseException;

    @PUT
    @Path("/suspend")
    @Consumes("application/json")
    Response suspend( SuspendDto suspendDto );
}
