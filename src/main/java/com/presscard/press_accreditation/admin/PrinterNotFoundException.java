package com.presscard.press_accreditation.admin;

/** No producer account with this identifier. */
public class PrinterNotFoundException extends RuntimeException {
    public PrinterNotFoundException(Long id) {
        super("Imprimeur introuvable : " + id);
    }
}
