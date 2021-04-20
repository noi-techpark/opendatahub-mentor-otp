import { legType } from "../../core-utils/types";
import PropTypes from "prop-types";
import React from "react";
import { withNamespaces } from "react-i18next"

import { formatDuration } from "../../core-utils/time";

import * as Styled from "../styled";

/**
 * This is a clickable component that summarizes the leg (travel time, stops
 * passed). On click it will expand and show the list of intermediate stops.
 */
function TransitLegSummary({ leg, onClick, stopsExpanded, t }) {
  return (
    <Styled.TransitLegSummary onClick={onClick}>
      {leg.duration && <span>{t('ride')} {formatDuration(leg.duration)}</span>}
      {leg.intermediateStops && (
        <span>
          {" / "}
          {leg.intermediateStops.length + 1}
          {` ${t('stops')} `}
          <Styled.CaretToggle expanded={stopsExpanded} />
        </span>
      )}
    </Styled.TransitLegSummary>
  );
}

TransitLegSummary.propTypes = {
  leg: legType.isRequired,
  onClick: PropTypes.func.isRequired,
  stopsExpanded: PropTypes.bool.isRequired
};

export default withNamespaces()(TransitLegSummary)
