import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { packageSelector } from '../../../selectors/PackageSelectors';
import { IPackage } from '../../utils/AxiosFunctions';
import ViewJsonContent from './ViewJsonContent';

interface IPublicProps {
  packageResource: string;
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  packagePayload: IPackage;
  isLoading: boolean;
  error: Error;
}

interface IState {
  data: string;
}

const PackageContainer = (props: IPrivateProps) => {
  const [data, setData] = React.useState('');

  React.useEffect(() => {
    setData(JSON.stringify(props.packagePayload.packageData, null, '\t'));
  }, [props.packagePayload, props.isLoading, props.error]);

  return (
    <div>
      {!!props.packagePayload && (
        <ViewJsonContent
          descriptor={props.packagePayload}
          data={data}
          usedBy={props.packagePayload.usedByBots}
          selectVersion={props.selectVersion}
        />
      )}
    </div>
  );
};

const ComposedPackageContainer: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('PackageContainer'),
  connect(packageSelector),
)(PackageContainer);

export default ComposedPackageContainer;
