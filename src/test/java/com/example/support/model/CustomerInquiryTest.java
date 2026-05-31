package com.example.support.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomerInquiryTest {

    @Test
    void recordAccessorsReturnConstructorValues() {
        CustomerInquiry inquiry = new CustomerInquiry(
                "CUST-001", "ORD-100", "Where is my order?", CustomerInquiry.InquiryType.ORDER);

        assertEquals("CUST-001", inquiry.customerId());
        assertEquals("ORD-100",  inquiry.orderId());
        assertEquals("Where is my order?", inquiry.text());
        assertEquals(CustomerInquiry.InquiryType.ORDER, inquiry.type());
    }

    @Test
    void orderIdIsNullableWithoutError() {
        CustomerInquiry inquiry = new CustomerInquiry(
                "CUST-002", null, "What is your return policy?", CustomerInquiry.InquiryType.FAQ);

        assertNull(inquiry.orderId());
    }

    @Test
    void equalityIsValueBased() {
        CustomerInquiry a = new CustomerInquiry("C1", null, "text", CustomerInquiry.InquiryType.UNKNOWN);
        CustomerInquiry b = new CustomerInquiry("C1", null, "text", CustomerInquiry.InquiryType.UNKNOWN);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void allInquiryTypesExist() {
        // Guard against accidental enum value removal
        assertNotNull(CustomerInquiry.InquiryType.valueOf("FAQ"));
        assertNotNull(CustomerInquiry.InquiryType.valueOf("ORDER"));
        assertNotNull(CustomerInquiry.InquiryType.valueOf("TECHNICAL"));
        assertNotNull(CustomerInquiry.InquiryType.valueOf("UNKNOWN"));
    }
}
