# Team and ownership

| Member | GitHub | Owns |
|---|---|---|
| Team lead | [@shadowfax-505](https://github.com/shadowfax-505) | Backend: domain model, database schema, security, business services and REST API. Frontend shell: login, layout, shared UI, routing. Docker setup and docs. Reviews and merges every pull request. |
| Member | [@Fatehkabir](https://github.com/Fatehkabir) | Student portal and staff portal (`frontend/src/pages/student`, `frontend/src/pages/staff`, `frontend/src/routes/student.jsx`, `frontend/src/routes/staff.jsx`) |
| Member | [@britto007](https://github.com/britto007) | Admin portal (`frontend/src/pages/admin`, `frontend/src/routes/admin.jsx`) and the backend test suite (`backend/src/test`) |

The frontend is split so that each area plugs in without touching the others. A portal module in `frontend/src/routes/` exports its pages, routes and sidebar entries. `App.jsx` and `Layout.jsx` read those modules, so adding a page means editing only your own module.

## Workflow

1. Branch from `main`, named after the feature, for example `feature/student-portal` or `feature/admin-portal`.
2. Commit in small steps with clear messages, and push your branch.
3. Open a pull request into `main` and request a review from the team lead.
4. The team lead reviews, asks for changes if needed, and merges.

Nobody pushes straight to `main` except the initial setup.

## Before you open a pull request

- Frontend: `cd frontend && npm install && npm run build` must succeed.
- Backend: `cd backend && mvn test` must pass.
- Run the app (see the README) and click through the pages you changed.
