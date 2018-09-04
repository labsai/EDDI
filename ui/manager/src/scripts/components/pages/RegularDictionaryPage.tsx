import * as React from 'react';

interface IRouteProps {
  match: { params: { type: string } };
}
interface IProps extends IRouteProps {}

const RegularDictionaryPage = (props: IProps) => <div />;

export default RegularDictionaryPage;
