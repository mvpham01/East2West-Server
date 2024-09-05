package com.east2west.controllers;

import com.east2west.config.PaymentConfig;
import com.east2west.models.DTO.BookingTourDTO;
import com.east2west.models.DTO.BookingTourFetch;
import com.east2west.models.DTO.CancelDTO;
import com.east2west.models.DTO.PaymentResDTO;
import com.east2west.models.DTO.RefundFetch;
import com.east2west.models.Entity.BookingTour;
import com.east2west.models.Entity.User;
import com.east2west.service.EmailService;
import com.east2west.service.PackTourService;
import com.east2west.service.UserService;
import com.east2west.models.Entity.User;
import io.jsonwebtoken.io.IOException;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletResponse;

import com.east2west.service.EmailService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;;

@RestController
@RequestMapping("/api/bookings")
public class BookingTourController {
    @Autowired
    private PackTourService packTourService;

    private EmailService emailService;

    private UserService userService;

    @GetMapping
    public List<BookingTourFetch> getBookingsTourList() {
        return packTourService.getAllBookingTours();
    }
    @GetMapping("/refund")
    public List<RefundFetch> getBookingsTourRefundList() {
        return packTourService.getAllRefunds();
    }
    @PostMapping()
    public ResponseEntity<String> createBookingTour(@RequestBody BookingTourDTO bookingTourDTO) {
        packTourService.saveBookingTour(bookingTourDTO);
        return ResponseEntity.ok("Booking tour created successfully!");
    }

    @PostMapping("/cancel")
    public ResponseEntity<String> cancelBooking(@RequestBody CancelDTO cancelDTO) {
        String response = packTourService.cancelBooking(cancelDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}")
    public List<BookingTour> getRentalsByUserId(@PathVariable int userId) {
        return packTourService.getListBookingByUser(userId);
    }

    @PostMapping("/create_payment/{amount}")
    public ResponseEntity<?> createPayment(@PathVariable long amount, @RequestBody BookingTourDTO bookingTourDTO)
            throws UnsupportedEncodingException {
        String orderType = "other";
        String bankCode = "NCB";
        String vnp_TxnRef = PaymentConfig.getRandomNumber(8);

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", PaymentConfig.vnp_Version);
        vnp_Params.put("vnp_Command", PaymentConfig.vnp_Command);
        vnp_Params.put("vnp_TmnCode", PaymentConfig.vnp_TmnCode);
        vnp_Params.put("vnp_Amount", String.valueOf(amount * 100)); // Convert to VND
        vnp_Params.put("vnp_CurrCode", "VND");

        if (bankCode != null && !bankCode.isEmpty()) {
            vnp_Params.put("vnp_BankCode", bankCode);
        }
        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", "Thanh toan don hang:" + bookingTourDTO.toParamString());
        vnp_Params.put("vnp_Locale", "vn");
        vnp_Params.put("vnp_OrderType", orderType);
        vnp_Params.put("vnp_ReturnUrl", PaymentConfig.vnp_ReturnUrl);
        vnp_Params.put("vnp_IpAddr", "127.0.0.1");

        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"));
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        String vnp_CreateDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        cld.add(Calendar.MINUTE, 15);
        String vnp_ExpireDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        for (String fieldName : fieldNames) {
            String fieldValue = vnp_Params.get(fieldName);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                hashData.append(fieldName).append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8.toString()));
                query.append(URLEncoder.encode(fieldName, StandardCharsets.UTF_8.toString())).append('=')
                        .append(URLEncoder.encode(fieldValue, StandardCharsets.UTF_8.toString()));
                if (!fieldName.equals(fieldNames.get(fieldNames.size() - 1))) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        }
        String queryUrl = query.toString();
        String vnp_SecureHash = PaymentConfig.hmacSHA512(PaymentConfig.secretKey, hashData.toString());
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;
        String paymentUrl = PaymentConfig.vnp_PayUrl + "?" + queryUrl;

        PaymentResDTO paymentResDTO = new PaymentResDTO();
        paymentResDTO.setStatus("OK");
        paymentResDTO.setMessage("Successfully");
        paymentResDTO.setURL(paymentUrl);

        return ResponseEntity.status(HttpStatus.OK).body(paymentResDTO);
    }

    @GetMapping("/payment_infor")
    public void transaction(
            @RequestParam(value = "vnp_Amount") String amount,
            @RequestParam(value = "vnp_BankCode") String bankCode,
            @RequestParam(value = "vnp_OrderInfo") String order,
            @RequestParam(value = "vnp_ResponseCode", required = false) String responseCode,
            HttpServletResponse response) throws IOException, MessagingException, java.io.IOException {
        if (responseCode != null && responseCode.equals("00")) {
            BookingTourDTO bookingTourDTO = parseOrderInfo(order);
            packTourService.saveBookingTour(bookingTourDTO);

            // Additional processing, like sending email
            // Assume userService and bookingTourService methods are available
            Optional<User> userAuth = userService.getUserById(bookingTourDTO.getUserId());
            if (userAuth.isPresent()) {
                // Replace with appropriate email sending logic
                // UserEmail userEmail = getUserEmail(userAuth, bookingTourDTO);
                // emailService.sendEmail(userEmail);
            }
        }
        String responseUrl = "http://localhost:3000/bookingtours";
        response.sendRedirect(responseUrl);
    }

    private BookingTourDTO parseOrderInfo(String orderInfo) {
        BookingTourDTO dto = new BookingTourDTO();

        // Adjust the pattern based on the actual orderInfo format
        Pattern pattern = Pattern.compile(
                "Tour Package ID: (\\d+), User ID: (\\d+), Tour Date: ([^,]+), Number of People: (\\d+), Total Price: ([\\d]+), Status: ([^,]*)");
        Matcher matcher = pattern.matcher(orderInfo);
        if (matcher.find()) {
            dto.setPackageId(Integer.parseInt(matcher.group(1)));
            dto.setUserId(Integer.parseInt(matcher.group(2)));
            dto.setTourDate(Timestamp.valueOf(matcher.group(3)));
            dto.setNumberOfPeople(Integer.parseInt(matcher.group(4)));
            dto.setTotalPrice(new BigDecimal(matcher.group(5)));
        }

        return dto;
    }
}