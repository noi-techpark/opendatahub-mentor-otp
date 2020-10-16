"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.default = void 0;

var _react = _interopRequireDefault(require("react"));

function _interopRequireDefault(obj) { return obj && obj.__esModule ? obj : { default: obj }; }

function _extends() { _extends = Object.assign || function (target) { for (var i = 1; i < arguments.length; i++) { var source = arguments[i]; for (var key in source) { if (Object.prototype.hasOwnProperty.call(source, key)) { target[key] = source[key]; } } } return target; }; return _extends.apply(this, arguments); }

const SvgWalk = ({
  title,
  ...props
}) => /*#__PURE__*/_react.default.createElement("svg", _extends({
  viewBox: "0 0 390 390"
}, props), title ? /*#__PURE__*/_react.default.createElement("title", null, title) : null, /*#__PURE__*/_react.default.createElement("path", {
  d: "m 207.60225,19.503527 c 16.02,0 29.07,13.05 29.07,29.07 0,16.020001 -13.05,29.07 -29.07,29.07 -16.02,0 -29.07,-13.049999 -29.07,-29.07 0,-16.02 13.05,-29.07 29.07,-29.07 z m 88.65,181.620003 c -4.05,5.94 -12.15,7.47 -18.09,3.42 0,0 -40.23,-27.45 -43.11,-29.43 -2.88,-1.98 -4.59,-6.12 -5.85,-8.82 -0.9,-1.8 -5.4,-10.62 -8.37,-16.47 l -3.6,15.3 -9.36,42.39 c 0,0 50.13,59.04 51.12,60.21 1.44,1.62 2.25,4.95 2.88,7.47 0.63,2.43 13.41,71.91 13.41,71.91 1.98,10.71 -5.13,21.06 -15.84,23.13 -10.71,1.98001 -21.06,-5.13 -23.13,-15.84 l -12.78,-68.67 -41.67,-45.54 c 0,0 -9.27,43.29 -9.45,44.19 -0.18,0.9 -0.63,2.25 -1.17,3.51 -0.54,1.17 -42.84,72.99 -42.84,72.99 -5.58,9.36 -17.73,12.51 -27.18,6.93 -9.359998,-5.58 -12.509998,-17.73 -6.929998,-27.18 l 37.619998,-63.27 30.24,-136.89 -20.34,16.2 -11.25,49.59 c 0,7.02 -7.92,11.61 -14.94,10.08 -7.02,-1.53 -11.16,-4.41 -9.54,-15.39 h 0.18 c 0,0 11.7,-55.08 12.6,-57.51 0.81,-2.43 2.07,-5.13 3.33,-6.12 8.46,-6.84 41.22,-33.66 50.85,-41.400003 11.07,-9.000001 15.93,-12.329999 25.38,-12.329999 7.11,0 14.85,2.159999 23.04,10.62 3.6,3.690001 6.21,9.720002 8.01,13.500002 1.8,3.69 24.39,48.33 24.39,48.33 l 38.97,27 c 5.94,4.05 7.47,12.15 3.42,18.09 z"
}));

var _default = SvgWalk;
exports.default = _default;