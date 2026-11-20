// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from 'react'

// Fluent "Cab" icon exported from "Journey Planner - Open Data Hub.fig", used in
// the map popup header. It takes the text colour (currentColor).
const Cab = ({ height = 15, width = 15, ...props }) => (
  <svg
    height={height}
    width={width}
    xmlns="http://www.w3.org/2000/svg"
    {...props}
    viewBox="0 0 20 20"
  >
    <path
      d="M5 11C5 10.448 4.552 10 4 10C3.448 10 3 10.448 3 11C3 11.552 3.448 12 4 12C4.552 12 5 11.552 5 11ZM13 11C13 10.448 12.552 10 12 10C11.448 10 11 10.448 11 11C11 11.552 11.448 12 12 12C12.552 12 13 11.552 13 11ZM5 2L4.14 2C2.948 2 1.922 2.841 1.688 4.01L1.49 5L0.75 5C0.336 5 0 5.336 0 5.75C0 6.164 0.336 6.5 0.75 6.5L1.19 6.5L1.079 7.06C0.455 7.242 0 7.818 0 8.5L0 13.5C0 14.328 0.672 15 1.5 15L2 15L2 16C2 16.552 2.448 17 3 17C3.552 17 4 16.552 4 16L4 15L12 15L12 16C12 16.552 12.448 17 13 17C13.552 17 14 16.552 14 16L14 15L14.5 15C15.328 15 16 14.328 16 13.5L16 8.5C16 7.818 15.545 7.242 14.921 7.06L14.809 6.5L15.25 6.5L15.352 6.493C15.718 6.443 16 6.13 16 5.75L15.993 5.648C15.944 5.282 15.63 5 15.25 5L14.509 5L14.312 4.01C14.078 2.841 13.052 2 11.86 2L11 2L11 0.75C11 0.336 10.664 0 10.25 0L5.75 0C5.336 0 5 0.336 5 0.75L5 2ZM6 2L6 1L10 1L10 2L6 2ZM1.5 8L14.5 8C14.776 8 15 8.224 15 8.5L15 13.5C15 13.776 14.776 14 14.5 14L1.5 14C1.224 14 1 13.776 1 13.5L1 8.5C1 8.224 1.224 8 1.5 8ZM4.14 3L11.86 3C12.575 3 13.191 3.505 13.331 4.206L13.89 7L2.11 7L2.669 4.206C2.809 3.505 3.425 3 4.14 3Z"
      transform="translate(2 1)"
      fill="currentColor"
    />
  </svg>
)

export default Cab
