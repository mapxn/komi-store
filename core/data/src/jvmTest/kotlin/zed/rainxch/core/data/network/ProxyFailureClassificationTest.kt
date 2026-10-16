package zed.rainxch.core.data.network

import zed.rainxch.core.domain.network.ProxyTestOutcome
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ProxyFailureClassificationTest {
    @Test
    fun socketTimeoutIsTimeout() {
        assertEquals(ProxyTestOutcome.Failure.Timeout, classifyProxyFailure(SocketTimeoutException("t")))
    }

    @Test
    fun unknownHostIsDnsFailure() {
        assertEquals(ProxyTestOutcome.Failure.DnsFailure, classifyProxyFailure(UnknownHostException("h")))
    }

    @Test
    fun connectExceptionIsProxyUnreachable() {
        assertEquals(ProxyTestOutcome.Failure.ProxyUnreachable, classifyProxyFailure(ConnectException("c")))
    }

    @Test
    fun causeChainIsInspected() {
        val wrapped = RuntimeException("outer", ConnectException("inner"))
        assertEquals(ProxyTestOutcome.Failure.ProxyUnreachable, classifyProxyFailure(wrapped))
    }

    @Test
    fun otherErrorsAreUnknownWithMessage() {
        assertEquals(
            ProxyTestOutcome.Failure.Unknown("boom"),
            classifyProxyFailure(IllegalStateException("boom")),
        )
    }
}
