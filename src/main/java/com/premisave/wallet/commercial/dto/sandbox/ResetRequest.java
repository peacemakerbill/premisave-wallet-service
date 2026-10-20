package com.premisave.wallet.commercial.dto.sandbox;

import java.util.List;

public record ResetRequest(String confirm, List<String> keep) {
}
