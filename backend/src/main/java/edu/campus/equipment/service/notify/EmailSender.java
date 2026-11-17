package edu.campus.equipment.service.notify;

/** Adapter boundary for outbound email; the core never talks to a mail provider directly. */
public interface EmailSender {
    void send(String to, String subject, String body) throws Exception;
}
