package dev.doughbay.fabric;

/** Closed planner gate dictionary; no runtime description is converted into report text. */
public enum DecisionGate {
    GATE_UNCLASSIFIED,
    GATE_ABOVE_BID_VALUE_CAP,
    GATE_ABOVE_WHAT_IT_REALLY_TRADES_AT,
    GATE_BELOW_BID_VALUE_FLOOR,
    GATE_BID_OR_RESERVE_ALREADY_OUT,
    GATE_CHEAP_SINGLE,
    GATE_CHEAPER_TO_BUY_ON_THE_AUCTION,
    GATE_FALLING,
    GATE_FAR_ABOVE_THE_STANDING_ORDERS,
    GATE_FULL_RESERVE_BATCH_WAITS_FOR_CAPS_DEPTH,
    GATE_LOW_CONFIDENCE,
    GATE_MARKET_ALREADY_AT_ITS_LISTING_CAP,
    GATE_NEEDS_LIVE_BID_PRICE,
    GATE_NO_MARGIN_AT_THE_GOING_ORDER_PRICE,
    GATE_NO_PRICES,
    GATE_NO_REAL_TRADES_OR_ORDERS_TO_PRICE_AGAINST,
    GATE_NO_RESALE_PRICE,
    GATE_NO_ROOM_LEFT,
    GATE_NO_WHOLE_STACK_FITS_THE_CAPS,
    GATE_NO_WHOLE_STACK_RESALE_PRICE_SINGLES_ARE_OF,
    GATE_NOT_ALLOWED_BY_ITEMS,
    GATE_ON_YOUR_OWN_PRICE_LIST,
    GATE_ONE_WHOLE_STACK_EXCEEDS_THE_BUY_CAP,
    GATE_ONLY_YOUR_PRICE_LIST_IS_TRADED,
    GATE_OUTBID_ALREADY,
    GATE_OVER_THE_BUY_CAP,
    GATE_PRICE_LOOKS_WRONG,
    GATE_RESERVE_REQUIRES_A_WHOLE_STACK_MARKET,
    GATE_RETAIL_BATCH_MARGIN_TOO_SMALL,
    GATE_RETAIL_NEEDS_A_STANDING_BID,
    GATE_RETAIL_STOCK_ALREADY_HELD,
    GATE_SERVER_REFUSES_AN_ORDER_FOR_IT,
    GATE_SINGLE_SLOTS_STILL_FULL,
    GATE_SINGLE_ITEM_SELLING_IS_OFF,
    GATE_THE_OTHER_ACCOUNT_S_MARKET,
    GATE_THINNER_THAN_ONE_STACK,
    GATE_TOO_FEW_SAMPLES,
    GATE_TOO_SLOW;
    public static DecisionGate of(String reason) {
        if(reason==null)return GATE_UNCLASSIFIED;
        return switch(reason) {
            case "above bid value cap" -> GATE_ABOVE_BID_VALUE_CAP;
            case "above what it really trades at" -> GATE_ABOVE_WHAT_IT_REALLY_TRADES_AT;
            case "below bid value floor" -> GATE_BELOW_BID_VALUE_FLOOR;
            case "bid or reserve already out" -> GATE_BID_OR_RESERVE_ALREADY_OUT;
            case "cheap single" -> GATE_CHEAP_SINGLE;
            case "cheaper to buy on the auction" -> GATE_CHEAPER_TO_BUY_ON_THE_AUCTION;
            case "falling" -> GATE_FALLING;
            case "far above the standing orders" -> GATE_FAR_ABOVE_THE_STANDING_ORDERS;
            case "full reserve batch waits for caps/depth" -> GATE_FULL_RESERVE_BATCH_WAITS_FOR_CAPS_DEPTH;
            case "low confidence" -> GATE_LOW_CONFIDENCE;
            case "market already at its listing cap" -> GATE_MARKET_ALREADY_AT_ITS_LISTING_CAP;
            case "needs live bid price" -> GATE_NEEDS_LIVE_BID_PRICE;
            case "no margin at the going order price" -> GATE_NO_MARGIN_AT_THE_GOING_ORDER_PRICE;
            case "no prices" -> GATE_NO_PRICES;
            case "no real trades or orders to price against" -> GATE_NO_REAL_TRADES_OR_ORDERS_TO_PRICE_AGAINST;
            case "no resale price" -> GATE_NO_RESALE_PRICE;
            case "no room left" -> GATE_NO_ROOM_LEFT;
            case "no whole stack fits the caps" -> GATE_NO_WHOLE_STACK_FITS_THE_CAPS;
            case "no whole-stack resale price; singles are off" -> GATE_NO_WHOLE_STACK_RESALE_PRICE_SINGLES_ARE_OF;
            case "not allowed by Items" -> GATE_NOT_ALLOWED_BY_ITEMS;
            case "on your own price list" -> GATE_ON_YOUR_OWN_PRICE_LIST;
            case "one whole stack exceeds the buy cap" -> GATE_ONE_WHOLE_STACK_EXCEEDS_THE_BUY_CAP;
            case "only your price list is traded" -> GATE_ONLY_YOUR_PRICE_LIST_IS_TRADED;
            case "outbid already" -> GATE_OUTBID_ALREADY;
            case "over the buy cap" -> GATE_OVER_THE_BUY_CAP;
            case "price looks wrong" -> GATE_PRICE_LOOKS_WRONG;
            case "reserve requires a whole-stack market" -> GATE_RESERVE_REQUIRES_A_WHOLE_STACK_MARKET;
            case "retail batch margin too small" -> GATE_RETAIL_BATCH_MARGIN_TOO_SMALL;
            case "retail needs a standing bid" -> GATE_RETAIL_NEEDS_A_STANDING_BID;
            case "retail stock already held" -> GATE_RETAIL_STOCK_ALREADY_HELD;
            case "server refuses an order for it" -> GATE_SERVER_REFUSES_AN_ORDER_FOR_IT;
            case "single slots still full" -> GATE_SINGLE_SLOTS_STILL_FULL;
            case "single-item selling is off" -> GATE_SINGLE_ITEM_SELLING_IS_OFF;
            case "the other account's market" -> GATE_THE_OTHER_ACCOUNT_S_MARKET;
            case "thinner than one stack" -> GATE_THINNER_THAN_ONE_STACK;
            case "too few samples" -> GATE_TOO_FEW_SAMPLES;
            case "too slow" -> GATE_TOO_SLOW;
            default -> GATE_UNCLASSIFIED;
        };
    }
}
