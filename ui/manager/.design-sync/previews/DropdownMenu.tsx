import {
  Button,
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuLabel,
} from "eddi-manager";
import { Copy, Download, Trash2 } from "lucide-react";

// Radix portals the content to <body>, so this card uses cardMode: single with
// a viewport — the same treatment AlertDialog needs.
export const Open = () => (
  <div style={{ padding: 16 }}>
    <DropdownMenu defaultOpen>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">Actions</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel>Agent</DropdownMenuLabel>
        <DropdownMenuItem><Copy /> Duplicate</DropdownMenuItem>
        <DropdownMenuItem><Download /> Export</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem><Trash2 /> Delete</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>
);
