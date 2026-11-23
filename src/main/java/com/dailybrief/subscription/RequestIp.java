package com.dailybrief.subscription;

import com.amazonaws.serverless.proxy.internal.servlet.AwsHttpApiV2ProxyHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.WebUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

final class RequestIp {
    private RequestIp() {}

    static String read(HttpServletRequest request) {
        var gateway = WebUtils.getNativeRequest(request, AwsHttpApiV2ProxyHttpServletRequest.class);
        if (gateway == null) return normalize(request.getRemoteAddr());

        var context = gateway.getRequest().getRequestContext();
        var http = context == null ? null : context.getHttp();
        return normalize(http == null ? null : http.getSourceIp());
    }

    private static String normalize(String address) {
        if (address != null && address.length() <= 45
                && (address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                    || (address.contains(":") && address.matches("[0-9A-Fa-f:.]+")))) {
            try {
                return InetAddress.getByName(address).getHostAddress();
            } catch (UnknownHostException invalid) {
                // Only numeric addresses are accepted.
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request IP");
    }
}
