package com.yuyay.notification.email;

public interface EmailSender {

    void send(String to, String subject, String html);
}