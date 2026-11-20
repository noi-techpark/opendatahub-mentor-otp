// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from 'react'

// Exported from "Journey Planner - Open Data Hub.fig", Guidelines > Mappa.
// stateColor is the border colour that carries the marker state (grey = no realtime data).
const StationCableCar = ({
  height = 36,
  stateColor = '#e3e4e7',
  width = 36,
  ...props
}) => (
  <svg
    height={height}
    width={width}
    xmlns="http://www.w3.org/2000/svg"
    {...props}
    viewBox="0 0 36 36"
  >
    <path
      d="M36 18C36 27.941 27.941 36 18 36C8.059 36 0 27.941 0 18C0 8.059 8.059 0 18 0C27.941 0 36 8.059 36 18Z"
      fill="#ffffff"
    />
    <clipPath id="StationCableCar-clip1">
      <path d="M36 18C36 27.941 27.941 36 18 36C8.059 36 0 27.941 0 18C0 8.059 8.059 0 18 0C27.941 0 36 8.059 36 18Z" />
    </clipPath>
    <path
      clipPath="url(#StationCableCar-clip1)"
      d="M36 18L34 18C34 26.837 26.837 34 18 34L18 36L18 38C29.046 38 38 29.046 38 18L36 18ZM18 36L18 34C9.163 34 2 26.837 2 18L0 18L-2 18C-2 29.046 6.954 38 18 38L18 36ZM0 18L2 18C2 9.163 9.163 2 18 2L18 0L18 -2C6.954 -2 -2 6.954 -2 18L0 18ZM18 0L18 2C26.837 2 34 9.163 34 18L36 18L38 18C38 6.954 29.046 -2 18 -2L18 0Z"
      fill={stateColor}
    />
    <path
      d="M28 14C28 21.732 21.732 28 14 28C6.268 28 0 21.732 0 14C0 6.268 6.268 0 14 0C21.732 0 28 6.268 28 14Z"
      fill="#ffffff"
      transform="translate(4 4)"
    />
    <path
      d="M20.064 0.006C20.42 -0.042 20.747 0.208 20.794 0.564C20.842 0.92 20.592 1.247 20.236 1.294L13 2.259L13 3.9C13 4.899 12.625 5.81 12.007 6.5L14.3 6.5C16.454 6.5 18.2 8.246 18.2 10.4L18.2 15.6C18.2 17.036 17.036 18.2 15.6 18.2L5.2 18.2C3.764 18.2 2.6 17.036 2.6 15.6L2.6 10.4C2.6 8.246 4.346 6.5 6.5 6.5L9.1 6.5C10.536 6.5 11.7 5.336 11.7 3.9L11.7 2.432L0.736 3.894C0.38 3.942 0.053 3.692 0.006 3.336C-0.042 2.98 0.208 2.653 0.564 2.606L11.7 1.121L11.7 0.65C11.7 0.291 11.991 0 12.35 0C12.709 0 13 0.291 13 0.65L13 0.948L20.064 0.006ZM16.9 10.4C16.9 8.964 15.736 7.8 14.3 7.8L6.5 7.8C5.064 7.8 3.9 8.964 3.9 10.4L3.9 13L16.9 13L16.9 10.4ZM3.9 14.3L3.9 15.6C3.9 16.318 4.482 16.9 5.2 16.9L15.6 16.9C16.318 16.9 16.9 16.318 16.9 15.6L16.9 14.3L3.9 14.3Z"
      fill="#000000"
      transform="translate(7.6 8.9)"
    />
  </svg>
)

export default StationCableCar
