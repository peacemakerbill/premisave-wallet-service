package com.premisave.wallet.commercial.dto;

import java.util.List;

public record ResetRequest(String confirm, List<String> keep) {
}
