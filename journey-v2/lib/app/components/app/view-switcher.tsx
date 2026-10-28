// SPDX-FileCopyrightText: 2024 Conveyal <support@conveyal.com>
//
// SPDX-License-Identifier: MIT

import { FormattedMessage, useIntl } from 'react-intl'
import React from 'react'

import Link from '@otp-react-redux/lib/components/util/link'

type Props = {
  sticky?: boolean
}

/**
 * Custom ViewSwitcher without route viewer link.
 */
const ViewSwitcher = ({ sticky }: Props) => {
  const intl = useIntl()
  return (
    <div
      aria-label={intl.formatMessage({
        id: 'components.ViewSwitcher.switcher'
      })}
      className="view-switcher"
      id="view-switcher"
      role="group"
      style={
        sticky
          ? {
              height: '100%',
              left: 0,
              position: 'absolute',
              width: '100%'
            }
          : {}
      }
    >
      <Link to="/" tracking>
        <FormattedMessage id="components.BatchRoutingPanel.shortTitle" />
      </Link>
      <Link to="/nearby" tracking>
        <FormattedMessage id="components.ViewSwitcher.nearby" />
      </Link>
    </div>
  )
}

export default ViewSwitcher
