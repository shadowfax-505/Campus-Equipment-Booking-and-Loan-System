import { Link } from 'react-router-dom';
import { useAuth } from '../auth';
import { Card } from '../components/ui';

/** Generic landing page, used for a role whose portal module does not provide its own dashboard. */
export default function Home() {
  const { user } = useAuth();
  return (
    <div className="stack">
      <h1>Welcome, {user.fullName}</h1>
      <Card title="Campus Equipment">
        <p className="muted">Signed in as {user.role.toLowerCase()}. Check your <Link to="/notifications">notifications</Link> or update your <Link to="/profile">profile</Link>.</p>
      </Card>
    </div>
  );
}
