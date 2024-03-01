package ru.arc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuctionItemDto {
    String display;
    String seller;
    String price;
    String expire;
    String category;
    int amount;
    int priority;
    List<String> lore = new ArrayList<>();
}
