package com.ridebooking.auth.application.otp;

/** Port: how a code reaches the user (email today, SMS later). */
public interface OtpSender {

    void send(String email, String code);
}
