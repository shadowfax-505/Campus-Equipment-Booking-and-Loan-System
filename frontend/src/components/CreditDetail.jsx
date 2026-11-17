import { fmtDate, label } from '../format';
import { Card, ScoreRing } from './ui';

const NAMES = { punctuality: 'Return punctuality', care: 'Equipment care', paymentTimeliness: 'Fine-payment timeliness', reliability: 'Collection & cancellation reliability', history: 'Successful borrowing history' };
const COUNTS = { closedLoans: 'Completed loans', onTimeReturns: 'On-time returns', lateReturns: 'Late returns', lostItems: 'Lost items', missedCollections: 'Missed collections', lateCancellations: 'Late cancellations', finesInPeriod: 'Fines' };

export default function CreditDetail({ c }) {
  return (
    <div className="stack">
      <div className="grid two">
        <Card title="Your borrowing score">
          <div className="row gap"><ScoreRing score={c.score} /><p className="muted">{c.explanation} When several students want the same item at the same time, the highest score gets priority. A high score never overrides an unpaid-fine block.</p></div>
        </Card>
        <Card title={`Counted events (last ${c.counts.recentHistoryDays} days)`}>
          <dl className="kv">{Object.entries(COUNTS).flatMap(([k, l]) => [<dt key={k}>{l}</dt>, <dd key={k + "v"}>{c.counts[k]}</dd>])}</dl>
        </Card>
      </div>
      <Card title="Factors">
        {Object.entries(c.components).map(([k, v]) => (
          <div key={k} className="factor">
            <div className="row between"><span>{NAMES[k]} <small className="muted">weight {(c.weights[k] * 100).toFixed(0)}%</small></span><b>{v.toFixed(1)}</b></div>
            <div className="bar"><i style={{ width: `${v}%` }} /></div>
            <small className="muted">contributes {c.contributions[k].toFixed(1)} points</small>
          </div>
        ))}
        {c.manualAdjustment !== 0 && <p>Administrator adjustments: <b>{c.manualAdjustment > 0 ? '+' : ''}{c.manualAdjustment.toFixed(1)}</b></p>}
      </Card>
      <Card title="Score history">
        <table>
          <thead><tr><th>When</th><th>Change</th><th>Reason</th><th>Event</th><th>Ref</th></tr></thead>
          <tbody>{c.history.map((h) => (
            <tr key={h.id} className={h.voided ? 'voided' : ''}>
              <td>{fmtDate(h.createdAt)}</td>
              <td>{h.previousScore.toFixed(1)} → <b>{h.newScore.toFixed(1)}</b>{h.adjustment !== 0 && <small> ({h.adjustment > 0 ? '+' : ''}{h.adjustment})</small>}</td>
              <td className="small">{h.reason}{h.components && <div className="muted">{h.components}</div>}</td>
              <td>{label(h.eventType)}</td>
              <td className="small">#{h.id}{h.loanId && ` · loan ${h.loanId}`}</td>
            </tr>
          ))}</tbody>
        </table>
      </Card>
    </div>
  );
}
