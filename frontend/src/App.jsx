import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth';
import Layout from './components/Layout';
import Login from './pages/Login';
import Home from './pages/Home';
import Notifications from './pages/Notifications';
import Profile from './pages/Profile';
import * as studentArea from './routes/student';
import * as staffArea from './routes/staff';
import * as adminArea from './routes/admin';

const mount = (list) => list.map(([path, element]) => <Route key={path} path={path} element={element} />);

export default function App() {
  const { user } = useAuth();
  if (!user) {
    return <Routes><Route path="*" element={<Login />} /></Routes>;
  }
  const student = user.role === 'STUDENT';
  const admin = user.role === 'ADMIN';
  const home = (student ? studentArea.home : staffArea.home) ?? <Home />;
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={home} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="profile" element={<Profile />} />
        {mount(studentArea.shared)}
        {student && mount(studentArea.routes)}
        {!student && mount(staffArea.routes)}
        {admin && mount(adminArea.routes)}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
