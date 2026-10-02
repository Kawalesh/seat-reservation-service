package com.paytm.reservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class ShowResponse {

    private String id;
    private String name;

    @JsonProperty("price_paise")
    private Long pricePaise;

    @JsonProperty("total_seats")
    private int totalSeats;

    private ShowCounts counts;

    private Map<String, String> seats;

    public ShowResponse() {
    }

    public ShowResponse(String id, String name, Long pricePaise, int totalSeats, ShowCounts counts, Map<String, String> seats) {
        this.id = id;
        this.name = name;
        this.pricePaise = pricePaise;
        this.totalSeats = totalSeats;
        this.counts = counts;
        this.seats = seats;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getPricePaise() {
        return pricePaise;
    }

    public void setPricePaise(Long pricePaise) {
        this.pricePaise = pricePaise;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public void setTotalSeats(int totalSeats) {
        this.totalSeats = totalSeats;
    }

    public ShowCounts getCounts() {
        return counts;
    }

    public void setCounts(ShowCounts counts) {
        this.counts = counts;
    }

    public Map<String, String> getSeats() {
        return seats;
    }

    public void setSeats(Map<String, String> seats) {
        this.seats = seats;
    }
}
