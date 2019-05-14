package org.cordacodeclub.bluff.privacy

import org.cordacodeclub.bluff.state.TokenState
import java.util.function.Predicate

class TokenStateFilter {

    companion object {
        fun tokenPredicate(): Predicate<Any> = Predicate {
            it is TokenState
        }
    }
}