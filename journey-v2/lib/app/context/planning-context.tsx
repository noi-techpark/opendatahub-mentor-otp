// SPDX-FileCopyrightText: 2025 NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: MIT

import React, { createContext, useContext, useMemo, useState } from 'react'

type PlanningContextValue = {
  isPlanning: boolean
  setIsPlanning: (v: boolean) => void
}

const defaultValue: PlanningContextValue = {
  isPlanning: false,
  setIsPlanning: () => {}
}

export const PlanningContext = createContext<PlanningContextValue>(defaultValue)

export function PlanningProvider({ children }: { children: React.ReactNode }) {
  const [isPlanning, setIsPlanning] = useState(false)
  const value = useMemo(() => ({ isPlanning, setIsPlanning }), [isPlanning])
  return <PlanningContext.Provider value={value}>{children}</PlanningContext.Provider>
}

export function usePlanning() {
  return useContext(PlanningContext)
}

