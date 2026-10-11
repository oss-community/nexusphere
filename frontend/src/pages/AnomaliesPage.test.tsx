import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { OPERATOR, anomaly, mockLedger, renderApp } from '../test-support'

describe('AnomaliesPage', () => {
  it('lists the open anomalies, checks for new ones and filters by status', async () => {
    const stored = [anomaly('n-1', 'VOLUME', 'OPEN'), anomaly('n-2', 'FAILURES', 'RESOLVED')]
    const calls = mockLedger({
      'GET /api/v1/anomalies': (call) => {
        const wanted = new URLSearchParams(call.path.split('?')[1]).get('status')
        return { body: { items: stored.filter((a) => !wanted || a.status === wanted) } }
      },
      'POST /api/v1/anomalies/detect': () => {
        const detected = anomaly('n-3', 'DENIALS', 'OPEN')
        stored.push(detected)
        return { body: { items: [detected] } }
      },
    })
    renderApp('/anomalies', OPERATOR)
    const user = userEvent.setup()

    const row = (await screen.findByText('n-1-agent')).closest('tr')!
    expect(within(row).getByText('VOLUME')).toBeInTheDocument()
    expect(within(row).getByText('240')).toBeInTheDocument()
    expect(within(row).getByText('31.5')).toBeInTheDocument()
    expect(within(row).getByText('12.3')).toBeInTheDocument()
    expect(within(row).getByText('OPEN')).toBeInTheDocument()
    expect(screen.queryByText('n-2-agent')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Check now' }))
    const detected = (await screen.findByText('n-3-agent')).closest('tr')!
    expect(within(detected).getByText('DENIALS')).toBeInTheDocument()
    expect(within(detected).getByText('42.5%')).toBeInTheDocument()
    expect(within(detected).getByText('5.0%')).toBeInTheDocument()
    expect(within(detected).getByText('4.1')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox'), 'RESOLVED')
    const resolved = (await screen.findByText('n-2-agent')).closest('tr')!
    expect(within(resolved).getByText('RESOLVED')).toBeInTheDocument()
    expect(within(resolved).getByText('FAILURES')).toBeInTheDocument()
    expect(resolved.querySelector('time[datetime="2026-10-08T12:00:00Z"]')).not.toBeNull()
    expect(screen.queryByText('n-1-agent')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox'), '')
    expect(await screen.findByText('n-1-agent')).toBeInTheDocument()
    expect(screen.getByText('n-2-agent')).toBeInTheDocument()

    expect(calls[0].path).toBe('/api/v1/anomalies?status=OPEN&limit=100')
    expect(calls.find((c) => c.path === '/api/v1/anomalies/detect')!.method).toBe('POST')
    expect(calls.some((c) => c.path === '/api/v1/anomalies?status=RESOLVED&limit=100')).toBe(true)
    expect(calls.some((c) => c.path === '/api/v1/anomalies?limit=100')).toBe(true)
    expect(screen.getByRole('link', { name: 'Anomalies' })).toBeInTheDocument()
  })

  it('shows when there are no anomalies', async () => {
    mockLedger({ 'GET /api/v1/anomalies': () => ({ body: { items: [] } }) })
    renderApp('/anomalies', OPERATOR)

    expect(await screen.findByText('No anomalies.')).toBeInTheDocument()
  })
})
