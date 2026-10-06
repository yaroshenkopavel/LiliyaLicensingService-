package pro.liliya.licensing.http

@Deprecated(
    message = "Fresh-install activation is authenticated by the signed Activation Code, not Product Auth.",
    replaceWith = ReplaceWith("ActivationRedemptionHttpEndpoint")
)
typealias AuthenticatedActivationRedemptionHttpEndpoint = ActivationRedemptionHttpEndpoint
