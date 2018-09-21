import * as React from 'react';

interface IRouteProps {
  match: { params: { type: string } };
}
interface IProps extends IRouteProps {}

const ExtensionsPage = (props: IProps) => <div />;

export default ExtensionsPage;
