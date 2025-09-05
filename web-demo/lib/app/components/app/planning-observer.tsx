import React, { useEffect } from 'react'
import { connect } from 'react-redux'
import { usePlanning } from '../../context/planning-context'

type Props = {
  from: any
  to: any
}

const PlanningObserver = ({ from, to }: Props) => {
  const { setIsPlanning } = usePlanning()
  useEffect(() => {
    if (!from && !to) {
      setIsPlanning(false)
    }
  }, [from, to, setIsPlanning])
  return null
}

const mapStateToProps = (state: any) => ({
  from: state.otp?.currentQuery?.from,
  to: state.otp?.currentQuery?.to
})

export default connect(mapStateToProps)(PlanningObserver)

