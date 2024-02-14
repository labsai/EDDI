import React from 'react';
import ReactDOM from 'react-dom/client';
import {BrowserRouter, Route, Routes} from "react-router-dom";
import Chat from './Chat';
import './index.css';

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
        <BrowserRouter>
            <Routes>
                <Route path="/chat/managedbots/:intent/:userId" element={<Chat />} />
                <Route path="/chat/:environment/:botId" element={<Chat />} />
            </Routes>
        </BrowserRouter>
);
