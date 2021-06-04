import * as _ from 'lodash';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../actions/ModalActionDispatchers';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import { IBot } from '../utils/AxiosFunctions';
import { ModalEnum } from '../utils/ModalEnum';
import PackageContainer from './PackageContainer';

const styles: { [key: string]: IExtendedCSSProperties } = {
  packagesHeader: {
    display: 'flex',
    marginTop: '50px',
  },
  headerCenter: {
    flex: 1,
  },
  packagesTitle: {
    color: '#54698D',
    fontSize: '12px',
    textAlign: 'left',
    marginTop: '10px',
  },
  button: {
    marginLeft: '10px',
  },
  packages: {
    marginBottom: '100px',
  },
};

interface IPublicProps {
  bot: IBot;
  readOnly: boolean;
}

interface IPrivateProps extends IPublicProps {}

interface IState {}

class PackageList extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
  }

  componentDidMount() {
    if (_.isUndefined(this.props.bot.packages)) {
      eddiApiActionDispatchers.fetchBotDataAction(this.props.bot);
    }
  }

  openModal = () => {
    ModalActionDispatchers.showModal(ModalEnum.createPackage);
  };

  openAddPackagesModal = () => {
    ModalActionDispatchers.showAddPackagesModal(this.props.bot);
  };

  render() {
    const isCurrentVersion =
      this.props.bot.version !== this.props.bot.currentVersion;
    return (
      <div style={styles.packages}>
        <div style={styles.packagesHeader}>
          <div style={styles.packagesTitle}>{'PACKAGES'}</div>
          <div style={styles.headerCenter} />
          <WhiteButton
            text={'Create package'}
            onClick={() => {
              this.openModal();
            }}
            disabled={isCurrentVersion || this.props.readOnly}
          />
          <WhiteButton
            text={'Add package'}
            onClick={this.openAddPackagesModal}
            disabled={isCurrentVersion || this.props.readOnly}
            customStyles={styles.button}
          />
        </div>
        {_.isEmpty(this.props.bot.packages) ? (
          <p>{`There are no packages yet`}</p>
        ) : (
          <div>
            {this.props.bot.packages.map((pack) => (
              <PackageContainer key={pack} packageResource={pack} />
            ))}
          </div>
        )}
      </div>
    );
  }
}

const ComposedPackageList: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  setDisplayName('PackageList'),
)(PackageList);

export default ComposedPackageList;
