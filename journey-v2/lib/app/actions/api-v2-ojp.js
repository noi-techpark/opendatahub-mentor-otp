// Replaces the vendor `actions/apiV2` module (see the NormalModuleReplacementPlugin
// in craco.config.js), so the OTP GraphQL calls can be moved to the OJP services
// one at a time without modifying the otp-react-redux submodule.
//
// Every action not overridden here is the vendor one. The import below uses the
// alias on purpose: the replacement only matches relative requests to apiV2,
// so this one still resolves to the original vendor file.
import vendorActions from '@otp-react-redux/lib/actions/apiV2'

export * from '@otp-react-redux/lib/actions/apiV2'

export default {
  ...vendorActions
}
