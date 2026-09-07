package pro.liliya.licensing.gcpkms

import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.FailedPreconditionException
import com.google.api.gax.rpc.NotFoundException
import com.google.api.gax.rpc.PermissionDeniedException
import com.google.cloud.kms.v1.AsymmetricSignRequest
import com.google.cloud.kms.v1.Digest
import com.google.cloud.kms.v1.GetPublicKeyRequest
import com.google.cloud.kms.v1.KeyManagementServiceClient
import com.google.protobuf.ByteString

/**
 * Thin production wrapper around Google Cloud KMS.
 *
 * Authentication is Application Default Credentials / Cloud Run service identity. No credentials
 * or private key bytes are accepted by this class.
 */
class GoogleCloudKmsClientAdapter(
    private val client: KeyManagementServiceClient
) : GcpKmsAsymmetricClient {

    override fun describe(cryptoKeyVersionName: String): GcpKmsDescribeResult =
        try {
            val publicKey = client.getPublicKey(
                GetPublicKeyRequest.newBuilder()
                    .setName(cryptoKeyVersionName)
                    .build()
            )
            GcpKmsDescribeResult.Available(
                GcpKmsKeyDescription(publicKey.algorithm.name)
            )
        } catch (_: NotFoundException) {
            GcpKmsDescribeResult.Unavailable
        } catch (_: FailedPreconditionException) {
            GcpKmsDescribeResult.Unavailable
        } catch (_: PermissionDeniedException) {
            GcpKmsDescribeResult.Failed
        } catch (_: ApiException) {
            GcpKmsDescribeResult.Failed
        } catch (_: RuntimeException) {
            GcpKmsDescribeResult.Failed
        }

    override fun signSha256Digest(
        cryptoKeyVersionName: String,
        digest: ByteArray
    ): GcpKmsSignResult =
        try {
            val request = AsymmetricSignRequest.newBuilder()
                .setName(cryptoKeyVersionName)
                .setDigest(
                    Digest.newBuilder()
                        .setSha256(ByteString.copyFrom(digest))
                        .build()
                )
                .build()
            val response = client.asymmetricSign(request)
            val signature = response.signature.toByteArray()
            if (signature.isEmpty()) {
                GcpKmsSignResult.Failed
            } else {
                GcpKmsSignResult.Signed(signature)
            }
        } catch (_: NotFoundException) {
            GcpKmsSignResult.Unavailable
        } catch (_: FailedPreconditionException) {
            GcpKmsSignResult.Unavailable
        } catch (_: PermissionDeniedException) {
            GcpKmsSignResult.Failed
        } catch (_: ApiException) {
            GcpKmsSignResult.Failed
        } catch (_: RuntimeException) {
            GcpKmsSignResult.Failed
        }
}
