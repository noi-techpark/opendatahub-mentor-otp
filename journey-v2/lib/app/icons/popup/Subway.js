// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from 'react'

// Fluent "Subway" icon exported from "Journey Planner - Open Data Hub.fig", used in
// the map popup header. It takes the text colour (currentColor).
const Subway = ({ height = 15, width = 15, ...props }) => (
  <svg
    height={height}
    width={width}
    xmlns="http://www.w3.org/2000/svg"
    {...props}
    viewBox="0 0 20 20"
  >
    <path
      d="M5 11C5 11.552 4.553 12 4 12C3.448 12 3 11.552 3 11C3 10.448 3.448 10 4 10C4.553 10 5 10.448 5 11ZM10 12C10.553 12 11 11.552 11 11C11 10.448 10.553 10 10 10C9.448 10 9 10.448 9 11C9 11.552 9.448 12 10 12ZM5.5 2C5.224 2 5 2.224 5 2.5C5 2.776 5.224 3 5.5 3L8.5 3C8.776 3 9 2.776 9 2.5C9 2.224 8.776 2 8.5 2L5.5 2ZM0 3C0 1.343 1.343 0 3 0L11 0C12.657 0 14 1.343 14 3L14 11C14 12.549 12.826 13.824 11.319 13.983L13.703 15.043C13.956 15.155 14.069 15.451 13.957 15.703C13.845 15.955 13.55 16.069 13.297 15.957L8.894 14L5.106 14L0.703 15.957C0.451 16.069 0.155 15.955 0.043 15.703C-0.069 15.451 0.045 15.155 0.297 15.043L2.682 13.983C1.175 13.824 0 12.549 0 11L0 3ZM9.012 13L11 13C12.105 13 13 12.105 13 11L13 9L1 9L1 11C1 12.105 1.896 13 3 13L4.989 13C4.996 13 5.004 13 5.011 13L8.99 13C8.997 13 9.004 13 9.012 13ZM1 3L1 8L13 8L13 3C13 1.895 12.105 1 11 1L3 1C1.896 1 1 1.895 1 3Z"
      transform="translate(3 3)"
      fill="currentColor"
    />
  </svg>
)

export default Subway
