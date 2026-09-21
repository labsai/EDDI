import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
  Button,
  Badge,
} from "eddi-manager";

export const AgentCard = () => (
  <div style={{ padding: 16, maxWidth: 420 }}>
    <Card>
      <CardHeader>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <CardTitle>Customer Support</CardTitle>
          <Badge variant="success">Deployed</Badge>
        </div>
        <CardDescription>
          Resolves tier-1 tickets and routes escalations to a human agent.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div style={{ display: "flex", gap: 24, fontSize: 14 }}>
          <div>
            <div style={{ fontWeight: 600 }}>1,284</div>
            <div style={{ color: "var(--color-muted-foreground)" }}>Conversations</div>
          </div>
          <div>
            <div style={{ fontWeight: 600 }}>96%</div>
            <div style={{ color: "var(--color-muted-foreground)" }}>Resolved</div>
          </div>
        </div>
      </CardContent>
      <CardFooter>
        <div style={{ display: "flex", gap: 8 }}>
          <Button size="sm">Open</Button>
          <Button size="sm" variant="outline">
            Configure
          </Button>
        </div>
      </CardFooter>
    </Card>
  </div>
);

export const SimpleCard = () => (
  <div style={{ padding: 16, maxWidth: 420 }}>
    <Card>
      <CardHeader>
        <CardTitle>API keys</CardTitle>
        <CardDescription>Manage credentials used to authenticate requests.</CardDescription>
      </CardHeader>
      <CardContent>
        <p style={{ fontSize: 14, color: "var(--color-muted-foreground)" }}>
          You have 3 active keys. Keys are shown only once at creation.
        </p>
      </CardContent>
    </Card>
  </div>
);
